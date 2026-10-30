// SDLC Orchestrator operator console (vanilla ES2022 modules, no build step).
// This module owns the single App state object, the server-sent event streams, the
// polling loops (the fallback when a stream is down, and a slow safety poll while it
// is up), the governance commands and the event wiring. Rendering is delegated to
// small pure functions in js/views.js and js/graph.js. Every screen region re-renders
// only when its data signature changes, so neither an event nor a poll ever
// interrupts someone typing into a form.
import { api, onReachability, RUN_LEASED_ELSEWHERE, EventStream, streamingSupported } from './js/api.js';
import { h, mount, store, shortId, humanize, isNum, asList, clip, fmtTime, fmtDateTime } from './js/util.js';
import { renderPipeline, renderLineage, renderLegend } from './js/graph.js';
import * as V from './js/views.js';

// safety: the slow poll kept while a server-sent event stream is open (a stream only carries this node's commits)
const POLL_MS = { runs: 3000, active: 2000, terminal: 10000, metrics: 5000, catalog: 30000, catalogRetry: 5000, safety: 30000 };
const TERMINAL = new Set(['COMPLETED', 'ABORTED']);
const TABS = ['artifacts', 'audit', 'lineage', 'metrics', 'policy', 'evaluation'];
const PAGE_SIZES = [25, 50, 100];
const RUN_STATUSES = ['RUNNING', 'WAITING_HUMAN', 'SAFE_STOPPED', 'COMPLETED', 'ABORTED'];
const LIST_SORT = 'createdAt,desc';
const OPERATOR_ID = /^[A-Za-z0-9._@-]{1,64}$/;
const FAULT_STAGES = ['intake', 'decompose', 'impact', 'design', 'implement', 'test-design', 'verify', 'document', 'security-scan', 'release-readiness'];
const AUDIT_PAGE = 500;
const VIEW_CACHE_MAX = 200;
const OFFLINE_TOAST = ['error', 'Backend unreachable', 'Polling continues; the console recovers on its own.'];

const $ = (id) => document.getElementById(id);
const enc = encodeURIComponent;

// ---------------------------------------------------------------- state

const storedSize = Number(store.get('sdlc.pageSize'));
const storedStatus = store.get('sdlc.statusFilter', '');

const App = {
  operatorId: store.get('sdlc.operatorId', '').trim(),
  online: true,
  providers: [],
  scenarios: null, // null until the catalog has loaded
  policy: null, // PolicyView (GET /api/v1/policy)
  runs: [], // RunSummaryView[] of the current page, newest first
  runsQuery: { page: 0, size: PAGE_SIZES.includes(storedSize) ? storedSize : 50, status: RUN_STATUSES.includes(storedStatus) ? storedStatus : '' },
  runsPage: { total: 0, totalPages: 0, loaded: false },
  streams: { list: null, run: null }, // EventStream of the runs list and of the selected run
  evaluation: { key: null, status: 'idle', report: null, error: null }, // GET /runs/{id}/evaluation of the selected run
  selectedId: null,
  runCtl: null, // AbortController shared by every request of the current selection
  run: null, // RunView
  graph: null, // GraphView
  artifacts: [], // ArtifactSummaryView[]
  audit: { events: [], lastSeq: 0, filter: new Set(), follow: true, open: new Set(), integrity: null }, // integrity: GET /audit/integrity
  lineage: null, // LineageView
  metrics: null, // ReliabilityMetricsView
  metricsAt: null,
  artifactSel: { stageId: null, version: null }, // version null = accepted (else latest)
  artifactViews: new Map(), // `${runId}|${stageId}|${version}` -> { status, view?, error?, stale?, refreshing? }
  artifactStateSig: null, // approvals and artifact summaries of the selected run when the cached views were last valid
  detailsOpen: { payload: false, provenance: false, policyInForce: false }, // expanded collapsible sections
  tab: TABS.includes(store.get('sdlc.tab')) ? store.get('sdlc.tab') : 'artifacts',
  drafts: {}, // values typed into the pending-actions card, keyed by data-draft
  busy: false, // a governance command is in flight
  sigs: {}, // last rendered data signature per screen region
};
const loops = {};

// ---------------------------------------------------------------- small helpers

const runPath = (id = App.selectedId) => `/api/v1/runs/${enc(id)}`;
const findByKey = (container, key) => [...container.querySelectorAll('[data-fk]')].find((el) => el.dataset.fk === key) ?? null;
const settle = (result, apply) => (result.status === 'fulfilled' ? apply(result.value) : pollError(result.reason));

function renderIfChanged(region, data, render) {
  const sig = JSON.stringify(data) ?? 'null';
  if (App.sigs[region] === sig) return;
  App.sigs[region] = sig;
  render();
}

/** Re-renders a container but keeps keyboard focus (and the caret) on the element with the same data-fk. */
function preservingFocus(container, render) {
  const active = document.activeElement;
  const key = active && container.contains(active) ? active.dataset?.fk : undefined;
  const caret = key && typeof active.selectionStart === 'number' ? [active.selectionStart, active.selectionEnd] : null;
  render();
  const next = key ? findByKey(container, key) : null;
  if (!next) return;
  next.focus({ preventScroll: true });
  if (caret) {
    try {
      next.setSelectionRange(...caret);
    } catch {
      /* not a text control */
    }
  }
}

function stageName(stageId) {
  const fromRun = asList(App.run?.stages).find((s) => s?.id === stageId)?.name;
  const fromGraph = asList(App.graph?.nodes).find((n) => n?.id === stageId)?.name;
  return fromRun || fromGraph || stageId || 'stage';
}

/** Stages in pipeline order (graph layer, then definition order) merged with RunView stage data. */
function orderedStages() {
  const runStages = new Map(asList(App.run?.stages).filter((s) => s?.id).map((s) => [s.id, s]));
  const nodes = asList(App.graph?.nodes).filter((n) => n?.id);
  const base = nodes.length ? [...nodes].sort((a, b) => (a.layer ?? 0) - (b.layer ?? 0)) : [...runStages.values()];
  return base.map((n) => ({ id: n.id, name: n.name ?? runStages.get(n.id)?.name, status: n.status ?? runStages.get(n.id)?.status }));
}

// ---------------------------------------------------------------- toasts, announcements, connectivity

const toasts = new Map();

function toast(kind, title, detail = '') {
  const key = `${kind}|${title}|${detail}`;
  let entry = toasts.get(key);
  if (entry) {
    entry.count += 1; // identical messages collapse into one toast with a counter
    entry.counter.textContent = ` \u00d7${entry.count}`;
  } else {
    entry = { count: 1, counter: h('span', { class: 'toast-count' }) };
    entry.el = h('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
      h('div', { class: 'toast-text' }, h('strong', {}, title), entry.counter, detail ? h('p', {}, detail) : null),
      h('button', { type: 'button', class: 'toast-close', 'aria-label': 'Dismiss notification', onclick: () => dismissToast(key) }, '\u00d7'));
    toasts.set(key, entry);
    $('toasts').append(entry.el);
  }
  clearTimeout(entry.timer);
  entry.timer = setTimeout(() => dismissToast(key), kind === 'error' ? 12_000 : 4_000);
}

function dismissToast(key) {
  const entry = toasts.get(key);
  if (!entry) return;
  clearTimeout(entry.timer);
  entry.el.remove();
  toasts.delete(key);
}

function toastError(err) {
  if (err?.aborted) return;
  if (err?.type === RUN_LEASED_ELSEWHERE) {
    // Another engine node holds the run's lease: name it, the conflict resolves itself once the run waits or ends.
    toast('error', `Run is executing on node ${err.holder ?? 'unknown'}`,
      'Another control plane node is executing this run right now. Retry when it waits for a human or has finished.');
    return;
  }
  toast('error', err?.title ?? 'Request failed', [err?.detail, ...asList(err?.errors)].filter(Boolean).join(' \u00b7 '));
}

/** Polling never blocks the UI: network failures only flip the indicator, HTTP errors raise a deduplicated toast. */
function pollError(err) {
  if (!err?.aborted && !err?.network) toastError(err);
}

function announce(message) {
  const region = $('announcer');
  region.textContent = '';
  setTimeout(() => {
    region.textContent = message;
  }, 50);
}

onReachability((ok) => {
  if (ok === App.online) return;
  App.online = ok;
  $('conn').hidden = ok;
  if (ok) {
    dismissToast(OFFLINE_TOAST.join('|'));
    toast('info', 'Backend reachable again');
  } else {
    toast(...OFFLINE_TOAST);
  }
  announce(ok ? 'Backend connection restored.' : 'Backend unreachable, retrying.');
});

// ---------------------------------------------------------------- polling

/** Runs `task` now and then every intervalMs(); never overlaps; kick() runs it again right away. */
function loop(task, intervalMs) {
  let timer = 0;
  let running = false;
  let again = false;
  const tick = async () => {
    clearTimeout(timer);
    if (running) {
      again = true;
      return;
    }
    running = true;
    try {
      await task();
    } catch (err) {
      console.error(err); // a render bug must not stop polling
    }
    running = false;
    if (again) {
      again = false;
      tick();
      return;
    }
    timer = setTimeout(tick, intervalMs() * (document.hidden ? 4 : 1));
  };
  tick();
  return { kick: tick };
}

async function refreshCatalog() {
  const [providers, scenarios, policy] = await Promise.allSettled([
    api('/api/v1/providers'),
    App.scenarios ? Promise.resolve(App.scenarios) : api('/api/v1/scenarios'),
    api('/api/v1/policy'),
  ]);
  settle(providers, (list) => {
    App.providers = asList(list).filter((p) => p?.id);
    renderProviders();
  });
  settle(policy, (view) => {
    App.policy = view && typeof view === 'object' ? view : null;
    renderPolicyChip();
    if (App.tab === 'policy') renderPolicyTab();
  });
  settle(scenarios, (list) => {
    if (App.scenarios) return;
    App.scenarios = asList(list).filter((s) => s?.key);
    mount($('nr-scenario'), h('option', { value: '' }, 'Custom requirement'),
      App.scenarios.map((s) => h('option', { value: s.key }, s.title || s.key)));
  });
}

function runsListPath() {
  const { page, size, status } = App.runsQuery;
  const params = new URLSearchParams({ page: String(page), size: String(size), sort: LIST_SORT });
  if (status) params.set('status', status);
  return `/api/v1/runs?${params}`;
}

async function refreshRuns() {
  try {
    const result = await api(runsListPath());
    const envelope = result && typeof result === 'object' && !Array.isArray(result) ? result : { items: asList(result) };
    App.runs = asList(envelope.items).filter((r) => r?.id);
    const total = isNum(envelope.total) ? envelope.total : App.runs.length;
    App.runsPage = { total, totalPages: isNum(envelope.totalPages) ? envelope.totalPages : 1, loaded: true };
    if (App.runsQuery.page > 0 && App.runsQuery.page >= App.runsPage.totalPages) {
      // the page emptied (runs were filtered out): show the last page that has rows
      App.runsQuery.page = Math.max(0, App.runsPage.totalPages - 1);
      loops.runs?.kick();
    }
  } catch (err) {
    pollError(err);
  }
  renderRunsList();
}

let runsRefetch = 0;
/** A run that is not on the current page changed: the first page may have a new row, refetch it soon. */
function scheduleRunsRefetch() {
  clearTimeout(runsRefetch);
  runsRefetch = setTimeout(() => loops.runs?.kick(), 400);
}

/** Applies a run-summary event of the list stream to the current page. */
function applyRunSummary(item) {
  if (!item?.id) return;
  const index = App.runs.findIndex((r) => r.id === item.id);
  if (index >= 0) {
    const matches = !App.runsQuery.status || App.runsQuery.status === item.status;
    if (matches) App.runs[index] = { ...App.runs[index], ...item };
    else scheduleRunsRefetch(); // it no longer matches the status filter
    renderRunsList();
  } else if (App.runsQuery.page === 0 && (!App.runsQuery.status || App.runsQuery.status === item.status)) {
    scheduleRunsRefetch();
  }
}

/** The runs list stream: summaries of every run change this node commits; the list poll slows down while it is open. */
function startListStream() {
  if (!streamingSupported) return;
  App.streams.list = new EventStream('/api/v1/runs/events', {
    onEvent: (event) => {
      if (event.type !== 'run-summary') return;
      applyRunSummary(parseData(event));
    },
    onState: (state) => {
      renderStreamState();
      // 'open': catch up with what changed while the stream was down; 'retrying' / 'failed': the stream died, so
      // the list poll falls back to its 3 s cadence now (its pending timer may have been set for the 30 s safety poll)
      if (state === 'open' || state === 'retrying' || state === 'failed') loops.runs?.kick();
    },
  }).start();
}

function parseData(event) {
  try {
    return JSON.parse(event.data);
  } catch {
    return null;
  }
}

/** Whether the selected run is held by another engine node, whose commits never reach this node's stream. */
function selectedRunOnOtherNode() {
  const node = App.streams.run?.nodeId;
  const summary = App.runs.find((r) => r.id === App.selectedId);
  return Boolean(node && summary?.nodeId && summary.nodeId !== node && !TERMINAL.has(App.run?.status));
}

function runPollInterval() {
  if (App.streams.run?.open && !selectedRunOnOtherNode()) return POLL_MS.safety;
  return TERMINAL.has(App.run?.status) ? POLL_MS.terminal : POLL_MS.active;
}

function runsPollInterval() {
  return App.streams.list?.open ? POLL_MS.safety : POLL_MS.runs;
}

async function fetchAudit(ctl) {
  for (let page = 0; page < 10; page++) {
    const batch = asList(await api(`${runPath()}/audit?afterSeq=${App.audit.lastSeq}&limit=${AUDIT_PAGE}`, { signal: ctl.signal }));
    if (ctl !== App.runCtl) return;
    const fresh = batch.filter((e) => e && Number(e.seq) > App.audit.lastSeq).sort((a, b) => a.seq - b.seq);
    if (!fresh.length) return;
    App.audit.events.push(...fresh);
    App.audit.lastSeq = Number(fresh.at(-1).seq);
    if (batch.length < AUDIT_PAGE) return;
  }
}

async function refreshSelectedRun() {
  const ctl = App.runCtl;
  if (!App.selectedId || !ctl) return;
  const base = runPath();
  const [run, graph, artifacts, audit, lineage, integrity] = await Promise.allSettled([
    api(base, { signal: ctl.signal }),
    api(`${base}/graph`, { signal: ctl.signal }),
    api(`${base}/artifacts`, { signal: ctl.signal }),
    fetchAudit(ctl),
    App.tab === 'lineage' ? api(`${base}/lineage`, { signal: ctl.signal }) : Promise.resolve(App.lineage),
    // The hash chain is re-verified with every audit poll while the Audit tab is open.
    App.tab === 'audit' ? api(`${base}/audit/integrity`, { signal: ctl.signal }) : Promise.resolve(App.audit.integrity),
  ]);
  if (ctl !== App.runCtl) return; // the selection changed while these requests were in flight
  if (run.status === 'rejected' && run.reason?.status === 404) {
    toast('error', 'Run not found', `There is no run with id ${clip(App.selectedId, 48)}.`);
    selectRun(null);
    return;
  }
  settle(run, applyRun);
  settle(graph, (g) => { App.graph = g; });
  settle(artifacts, (list) => { App.artifacts = asList(list).filter((a) => a?.stageId); });
  settle(audit, () => {});
  settle(lineage, (l) => { App.lineage = l; });
  settle(integrity, (v) => { App.audit.integrity = v && typeof v === 'object' ? v : null; });
  invalidateViewsOnChange();
  renderRunView();
}

/**
 * After a streamed run change: the pipeline graph, the artifact list (and the lineage or the chain verification when
 * their tab is open) are not part of the stream, so they are refetched once the burst of events has settled.
 */
let extrasTimer = 0;
function scheduleRunExtras() {
  clearTimeout(extrasTimer);
  extrasTimer = setTimeout(refreshRunExtras, 250);
}

async function refreshRunExtras() {
  const ctl = App.runCtl;
  if (!App.selectedId || !ctl) return;
  const base = runPath();
  const [graph, artifacts, lineage, integrity] = await Promise.allSettled([
    api(`${base}/graph`, { signal: ctl.signal }),
    api(`${base}/artifacts`, { signal: ctl.signal }),
    App.tab === 'lineage' ? api(`${base}/lineage`, { signal: ctl.signal }) : Promise.resolve(App.lineage),
    App.tab === 'audit' ? api(`${base}/audit/integrity`, { signal: ctl.signal }) : Promise.resolve(App.audit.integrity),
  ]);
  if (ctl !== App.runCtl) return;
  settle(graph, (g) => { App.graph = g; });
  settle(artifacts, (list) => { App.artifacts = asList(list).filter((a) => a?.stageId); });
  settle(lineage, (l) => { App.lineage = l; });
  settle(integrity, (v) => { App.audit.integrity = v && typeof v === 'object' ? v : null; });
  invalidateViewsOnChange();
  renderRunView();
}

/** Adds streamed audit events (sequence order, no duplicates) to the audit trail of the selected run. */
function applyAuditEvent(event) {
  if (!event || !isNum(Number(event.seq))) return;
  const seq = Number(event.seq);
  if (seq <= App.audit.lastSeq) return;
  App.audit.events.push(event);
  App.audit.lastSeq = seq;
  scheduleAuditRender();
}

let auditRenderTimer = 0;
function scheduleAuditRender() {
  clearTimeout(auditRenderTimer);
  auditRenderTimer = setTimeout(() => {
    if (App.tab === 'audit') renderAuditTab();
  }, 100);
}

/** The stream of the selected run: its audit events and run views as this node commits them. */
function startRunStream(id) {
  App.streams.run?.close();
  App.streams.run = null;
  if (!id || !streamingSupported) {
    renderStreamState();
    return;
  }
  const stream = new EventStream(`${runPath(id)}/events`, {
    lastEventId: () => App.audit.lastSeq,
    onEvent: (event) => {
      if (stream !== App.streams.run) return;
      const data = parseData(event);
      if (event.type === 'audit') applyAuditEvent(data);
      else if (event.type === 'run' && data?.id === App.selectedId) {
        applyRun(data);
        renderRunView();
        scheduleRunExtras();
      }
    },
    // the server ends the stream after the final event of a COMPLETED or ABORTED run: no reconnect then
    onEnd: () => !TERMINAL.has(App.run?.status),
    onState: (state) => {
      if (stream !== App.streams.run) return;
      renderStreamState();
      if (state === 'open' || state === 'retrying' || state === 'failed') loops.run?.kick();
    },
  });
  App.streams.run = stream;
  stream.start();
  renderStreamState();
}

/**
 * What a cached artifact view can go stale on: a decided or revoked approval flips the accepted flag of a version,
 * a rework or replan adds versions. The payload and Markdown of a version never change.
 */
function artifactStateSignature() {
  const approvals = asList(App.run?.pendingActions).filter((a) => a?.type === 'APPROVAL').map((a) => [a.approvalId, a.stageId, a.reviewVersion]);
  const accepted = asList(App.run?.stages).map((s) => [s?.id, s?.acceptedVersion]);
  const summaries = App.artifacts.map((a) => [a.stageId, a.version, a.accepted, a.hash]);
  return JSON.stringify([approvals, accepted, summaries]);
}

/** Marks the cached views of the selected run stale when its approvals or artifact summaries changed. */
function invalidateViewsOnChange() {
  const sig = artifactStateSignature();
  if (App.artifactStateSig !== null && App.artifactStateSig !== sig) invalidateArtifactViews(App.selectedId);
  App.artifactStateSig = sig;
}

/** Ready views stay on screen and are refetched in the background; failed ones are dropped so they load again. */
function invalidateArtifactViews(runId) {
  for (const [key, state] of App.artifactViews) {
    if (!key.startsWith(`${runId}|`)) continue;
    if (state.status === 'ready') state.stale = true;
    else if (state.status === 'error') App.artifactViews.delete(key);
  }
}

const evaluationKey = () => (App.selectedId ? `${App.selectedId}|${App.artifacts.map((a) => `${a.stageId}:${a.version}:${a.accepted}`).join(',')}` : null);

/** Evaluates the selected run while the Evaluation tab is open, again whenever its artifacts change. */
async function refreshEvaluation({ force = false } = {}) {
  if (App.tab !== 'evaluation' || !App.selectedId) {
    renderEvaluationTab();
    return;
  }
  const key = evaluationKey();
  if (!force && App.evaluation.key === key && App.evaluation.status !== 'error') {
    renderEvaluationTab();
    return;
  }
  const ctl = App.runCtl;
  App.evaluation = { ...App.evaluation, key, status: App.evaluation.report ? 'refreshing' : 'loading', error: null };
  renderEvaluationTab();
  try {
    const report = await api(`${runPath()}/evaluation`, { signal: ctl?.signal });
    if (ctl !== App.runCtl) return;
    App.evaluation = { key, status: 'ready', report, error: null };
  } catch (err) {
    if (err?.aborted || ctl !== App.runCtl) return;
    App.evaluation = { key, status: 'error', report: App.evaluation.report, error: err };
    pollError(err);
  }
  renderEvaluationTab();
}

async function refreshMetrics() {
  if (App.tab !== 'metrics') return;
  try {
    App.metrics = await api('/api/v1/metrics/reliability');
    App.metricsAt = new Date().toISOString();
  } catch (err) {
    pollError(err);
  }
  renderMetricsTab();
}

function applyRun(run) {
  if (!run || typeof run !== 'object') return;
  const prev = App.run;
  App.run = run;
  const pending = asList(run.pendingActions).length;
  if (!prev) announce(`Run ${shortId(run.id)}: ${humanize(run.status)}.`);
  else if (prev.status !== run.status) announce(`Run ${shortId(run.id)} is now ${humanize(run.status)}.`);
  else if (pending > asList(prev.pendingActions).length) announce(`${pending} pending action${pending === 1 ? '' : 's'} need attention.`);
  const summary = App.runs.find((r) => r.id === run.id);
  if (summary) {
    // Reflect the fresh state in the sidebar right away instead of waiting for the next list update.
    const succeeded = asList(run.stages).filter((s) => s?.status === 'SUCCEEDED').length;
    Object.assign(summary, {
      status: run.status, haltReason: run.haltReason, pendingActions: pending, updatedAt: run.updatedAt,
      progress: { succeeded, total: asList(run.stages).length },
    });
    renderRunsList();
  }
}

// ---------------------------------------------------------------- selection

function runIdFromHash() {
  const match = location.hash.match(/^#run=(.+)$/);
  try {
    return match ? decodeURIComponent(match[1]) : null;
  } catch {
    return null;
  }
}

function selectRun(id, { fromHash = false } = {}) {
  const next = id || null;
  if (next === App.selectedId) return;
  App.runCtl?.abort();
  Object.assign(App, {
    selectedId: next,
    runCtl: next ? new AbortController() : null,
    run: null, graph: null, artifacts: [], lineage: null, drafts: {},
    artifactSel: { stageId: null, version: null }, artifactStateSig: null,
    audit: { events: [], lastSeq: 0, filter: new Set(), follow: App.audit.follow, open: new Set(), integrity: null },
    evaluation: { key: null, status: 'idle', report: null, error: null },
    sigs: { providers: App.sigs.providers, policyChip: App.sigs.policyChip },
  });
  if (!fromHash) history.replaceState(null, '', next ? `#run=${enc(next)}` : `${location.pathname}${location.search}`);
  startRunStream(next);
  renderRunsList();
  renderRunView();
  loops.run?.kick();
}

/** Versions under review by stage: an exit approval's artifact is accepted only once the approval is granted. */
function reviewVersions() {
  const reviewing = new Map();
  for (const a of asList(App.run?.pendingActions)) {
    if (a?.type === 'APPROVAL' && a.stageId && isNum(a.reviewVersion)) reviewing.set(a.stageId, a.reviewVersion);
  }
  return reviewing;
}

function selectedSummary() {
  const { stageId, version } = App.artifactSel;
  if (!stageId) return null;
  const versions = App.artifacts.filter((a) => a.stageId === stageId);
  if (version != null) return versions.find((a) => a.version === version) ?? null;
  // Default: the version awaiting approval (what the operator must review), else the accepted one, else the latest.
  const review = reviewVersions().get(stageId);
  return versions.find((a) => review != null && a.version === review)
    ?? versions.find((a) => a.accepted)
    ?? versions.reduce((best, a) => (!best || (a.version ?? 0) > (best.version ?? 0) ? a : best), null);
}

const viewKey = (summary) => `${App.selectedId}|${summary.stageId}|${summary.version}`;

async function ensureArtifactView(summary, { force = false } = {}) {
  const key = viewKey(summary);
  const existing = App.artifactViews.get(key);
  const refresh = Boolean(existing?.stale && !existing.refreshing);
  if (existing && !(force && existing.status === 'error') && !refresh) return;
  const ctl = App.runCtl;
  // A stale view stays visible while it is refetched; a first load shows the loading placeholder.
  if (refresh) existing.refreshing = true;
  else App.artifactViews.set(key, { status: 'loading' });
  const query = isNum(summary.version) ? `?version=${summary.version}` : '';
  try {
    const view = await api(`${runPath()}/artifacts/${enc(summary.stageId)}${query}`, { signal: ctl?.signal });
    App.artifactViews.set(key, { status: 'ready', view: view ?? {} });
  } catch (err) {
    if (refresh) {
      existing.refreshing = false; // keep the stale view; the next render retries
      if (!err.aborted) pollError(err);
      return;
    }
    if (err.aborted) {
      App.artifactViews.delete(key);
      return;
    }
    App.artifactViews.set(key, { status: 'error', error: err });
    toastError(err);
  }
  while (App.artifactViews.size > VIEW_CACHE_MAX) App.artifactViews.delete(App.artifactViews.keys().next().value);
  if (ctl === App.runCtl) renderActiveTab();
}

function selectArtifact(stageId, version = null, { showTab = true, reveal = false } = {}) {
  App.artifactSel = { stageId, version };
  const summary = selectedSummary();
  if (summary) ensureArtifactView(summary, { force: true });
  if (showTab && App.tab !== 'artifacts') setTab('artifacts');
  else renderActiveTab();
  renderGraph();
  if (reveal) {
    const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    $('details-card').scrollIntoView({ block: 'nearest', behavior: smooth ? 'smooth' : 'auto' });
  }
}

// ---------------------------------------------------------------- governance commands

function requireOperator() {
  if (OPERATOR_ID.test(App.operatorId)) return true;
  const input = $('operator-id');
  input.setAttribute('aria-invalid', 'true');
  input.focus();
  toast('error', 'Operator id required', 'Enter your operator id (top right). It is sent as X-Operator-Id and recorded with every action. Allowed: letters, digits and . _ @ -');
  return false;
}

async function command(successMessage, path, body, clearKeys = []) {
  if (!requireOperator()) return;
  App.busy = true;
  renderActionsCard();
  try {
    const run = await api(path, { method: 'POST', body, operatorId: App.operatorId, timeoutMs: 30_000 });
    for (const key of clearKeys) delete App.drafts[key];
    App.sigs.actions = null; // force the card to show the cleared inputs
    if (run?.id && run.id === App.selectedId) applyRun(run);
    toast('success', successMessage);
  } catch (err) {
    toastError(err);
    if (err?.type === RUN_LEASED_ELSEWHERE) announce(`Run is executing on node ${err.holder ?? 'unknown'}; retry when it waits for a human.`);
    else if (err?.status === 409) announce('The run changed before your command arrived; the latest state is shown.');
  } finally {
    App.busy = false;
    renderRunView();
    loops.run?.kick();
    loops.runs?.kick();
  }
}

const draftValue = (key) => String(App.drafts[key] ?? '').trim();
const focusDraft = (key) => findByKey($('actions-card'), key)?.focus();

// Approvals and rejections carry the approvalId that was rendered, so a decision never applies to a newer
// pending approval (for example after a replan): the server answers 409 and the card refreshes.
const commands = {
  approve(stageId, approvalId) {
    const comment = draftValue(`comment:${stageId}`);
    const body = { ...(comment ? { comment } : {}), ...(approvalId ? { approvalId } : {}) };
    command(`Approved ${stageName(stageId)}`, `${runPath()}/stages/${enc(stageId)}/approve`, body,
      [`comment:${stageId}`, `reason:${stageId}`]);
  },
  review(stageId, version) {
    selectArtifact(stageId, version, { reveal: true });
  },
  reject(stageId, approvalId) {
    const reason = draftValue(`reason:${stageId}`);
    const disposition = App.drafts[`disposition:${stageId}`] || 'REWORK';
    if (!reason) {
      toast('error', 'Rejection reason required', 'The reason is fed back to the agent (rework) and kept in the audit trail.');
      focusDraft(`reason:${stageId}`);
      return;
    }
    if (disposition === 'ABORT' && !window.confirm(`Reject ${stageName(stageId)} and abort the whole run?`)) return;
    command(`Rejected ${stageName(stageId)} (${humanize(disposition)})`, `${runPath()}/stages/${enc(stageId)}/reject`,
      { reason, disposition, ...(approvalId ? { approvalId } : {}) },
      [`comment:${stageId}`, `reason:${stageId}`, `disposition:${stageId}`]);
  },
  clarify(questionIds) {
    const answers = Object.fromEntries(questionIds.map((id) => [id, draftValue(`clar:${id}`)]));
    const missing = questionIds.filter((id) => !answers[id]);
    if (missing.length) {
      toast('error', 'Answer every question', `${missing.length} question${missing.length === 1 ? ' is' : 's are'} still empty.`);
      focusDraft(`clar:${missing[0]}`);
      return;
    }
    command('Clarification submitted', `${runPath()}/clarify`, { answers }, questionIds.map((id) => `clar:${id}`));
  },
  fill(key, value) {
    App.drafts[key] = value;
    const el = findByKey($('actions-card'), key);
    if (el) {
      el.value = value;
      el.focus();
    }
  },
  replan() {
    const requirement = String(App.drafts.replan ?? App.run?.activeRequirement ?? '').trim();
    if (requirement.length < 10 || requirement.length > 4000) {
      toast('error', 'Requirement length', 'The changed requirement needs 10 to 4000 characters.');
      focusDraft('replan');
      return;
    }
    command('Replan requested', `${runPath()}/replan`, { requirement }, ['replan']);
  },
  stop() {
    command('Run stopped', `${runPath()}/stop`, { reason: draftValue('stop') || 'Stopped from the operator console' }, ['stop']);
  },
  resume() {
    command('Run resumed', `${runPath()}/resume`, {});
  },
  toggleReplan(open) {
    App.drafts['replan:open'] = String(open);
  },
};

// ---------------------------------------------------------------- rendering

/** Chip for an LLM provider whose circuit breaker is not closed (its attempts run the deterministic agents). */
function circuitChip(circuit) {
  if (!circuit || !circuit.state || circuit.state === 'CLOSED') return null;
  const open = circuit.state === 'OPEN';
  const failures = isNum(circuit.consecutiveFailures) ? `${circuit.consecutiveFailures} consecutive failure${circuit.consecutiveFailures === 1 ? '' : 's'}` : null;
  const title = [
    open ? `Attempts use the deterministic agents until ${fmtDateTime(circuit.retryAt)}` : 'The next attempt tries the provider again',
    failures, circuit.lastFailure ? `Last failure: ${circuit.lastFailure}` : null,
  ].filter(Boolean).join('\n');
  return h('span', { class: `circuit ${open ? 'open' : 'half-open'}`, title },
    open ? `circuit open until ${fmtTime(circuit.retryAt)}` : 'circuit half-open');
}

function renderProviders() {
  renderIfChanged('providers', App.providers, () => {
    mount($('providers'), App.providers.map((p) =>
      h('span', { class: `provider ${p.available ? 'up' : 'down'}`, title: [p.model, p.detail].filter(Boolean).join(' \u2014 ') || null },
        h('span', { class: 'dot', 'aria-hidden': 'true' }), p.id,
        p.model ? h('span', { class: 'model' }, p.model) : null,
        circuitChip(p.circuit),
        h('span', { class: 'sr-only' }, p.available ? ' (available)' : ' (unavailable)'))));
    const select = $('nr-provider');
    const current = select.value;
    const circuitNote = (p) => (p.circuit && p.circuit.state !== 'CLOSED' ? ` (circuit ${humanize(p.circuit.state).toLowerCase()})` : '');
    mount(select, h('option', { value: '' }, 'default'), App.providers.map((p) =>
      h('option', { value: p.id, disabled: !p.available }, `${p.id}${p.model ? ` \u00b7 ${p.model}` : ''}${p.available ? circuitNote(p) : ' (unavailable)'}`)));
    select.value = App.providers.some((p) => p.id === current && p.available) ? current : '';
  });
}

function renderRunsList() {
  renderIfChanged('runs', [App.runs, App.selectedId], () => {
    const list = $('runs-list');
    preservingFocus(list, () => mount(list, V.renderRunsList(App.runs, App.selectedId, (id) => selectRun(id))));
  });
  renderIfChanged('runs-pager', [App.runsQuery, App.runsPage], () => {
    const pager = $('runs-pager');
    $('runs-count').textContent = App.runsPage.total ? String(App.runsPage.total) : '';
    preservingFocus(pager, () => mount(pager, V.renderPager({
      ...App.runsQuery, ...App.runsPage, sizes: PAGE_SIZES, statuses: RUN_STATUSES, on: pagerHandlers,
    })));
  });
}

const pagerHandlers = {
  page(page) {
    App.runsQuery.page = Math.max(0, page);
    loops.runs?.kick();
    renderRunsList();
  },
  size(size) {
    const value = Number(size);
    if (!PAGE_SIZES.includes(value)) return;
    App.runsQuery = { ...App.runsQuery, size: value, page: 0 };
    store.set('sdlc.pageSize', String(value));
    loops.runs?.kick();
    renderRunsList();
  },
  status(status) {
    App.runsQuery = { ...App.runsQuery, status: RUN_STATUSES.includes(status) ? status : '', page: 0 };
    store.set('sdlc.statusFilter', App.runsQuery.status);
    loops.runs?.kick();
    renderRunsList();
  },
};

/** Live / polling indicator of the streams (the console polls while a stream is down). */
function renderStreamState() {
  const list = App.streams.list?.state ?? 'closed';
  const run = App.streams.run?.state ?? (App.selectedId ? 'closed' : null);
  renderIfChanged('stream-state', [list, run, streamingSupported], () => {
    const live = list === 'open' && (run === null || run === 'open' || (run === 'closed' && TERMINAL.has(App.run?.status)));
    const el = $('live');
    el.textContent = live ? 'Live' : 'Polling';
    el.className = `live ${live ? 'on' : 'off'}`;
    el.title = !streamingSupported ? 'This browser cannot read event streams; the console polls.'
      : live ? 'Server-sent events: changes arrive as they are committed (plus a slow safety poll).'
        : 'The event stream is reconnecting; the console polls meanwhile.';
  });
}

function renderPolicyChip() {
  renderIfChanged('policyChip', App.policy, () => mount($('policy-chip'), V.renderPolicyChip(App.policy, () => setTab('policy'))));
}

function renderRunView() {
  const hasRun = Boolean(App.selectedId);
  $('empty-state').hidden = hasRun;
  $('run-view').hidden = !hasRun;
  if (hasRun) {
    renderIfChanged('banner', App.run, () => mount($('status-banner'), App.run ? V.renderBanner(App.run) : V.placeholder('Loading run\u2026')));
    renderActionsCard();
    renderGraph();
  }
  renderActiveTab();
}

function renderActionsCard() {
  const run = App.run;
  const data = run && [run.status, run.haltReason, run.pendingActions, run.activeRequirement, run.requirement,
    run.counters?.replans, run.budget?.maxReplans, asList(run.stages).map((s) => s?.name), App.busy];
  renderIfChanged('actions', data, () => {
    const card = $('actions-card');
    card.hidden = !run;
    preservingFocus(card, () => mount(card, run ? V.renderActions(run, { drafts: App.drafts, busy: App.busy, stageName, on: commands }) : null));
  });
}

function renderGraph() {
  renderIfChanged('graph', [App.graph, App.artifactSel.stageId], () => {
    const container = $('graph');
    const onSelect = (stageId) => selectArtifact(stageId, null, { reveal: true });
    preservingFocus(container, () => mount(container,
      renderPipeline(App.graph, { selected: App.artifactSel.stageId, onSelect })
        ?? V.placeholder(App.graph ? 'This workflow has no stages.' : 'Loading pipeline\u2026')));
  });
}

function renderActiveTab() {
  ({
    artifacts: renderArtifactsTab, audit: renderAuditTab, lineage: renderLineageTab, metrics: renderMetricsTab,
    policy: renderPolicyTab, evaluation: () => refreshEvaluation(),
  })[App.tab]();
}

function renderArtifactsTab() {
  if (!App.selectedId) {
    renderIfChanged('tab:artifacts', 'none', () => {
      mount($('art-list'));
      mount($('art-detail'), V.placeholder('Select a run to browse its artifacts.'));
    });
    return;
  }
  const stages = orderedStages();
  if (!App.artifactSel.stageId && App.artifacts.length) {
    // Default selection: the earliest pipeline stage that already produced an artifact.
    const first = stages.find((s) => App.artifacts.some((a) => a.stageId === s.id))?.id ?? App.artifacts[0].stageId;
    App.artifactSel = { stageId: first, version: null };
  }
  const summary = selectedSummary();
  if (summary) ensureArtifactView(summary);
  const selectedKey = summary ? `${summary.stageId}|${summary.version}` : null;
  const reviewing = reviewVersions();
  const listSig = App.artifacts.map((a) => [a.stageId, a.version, a.accepted, a.kind, a.hash]);
  renderIfChanged('tab:artifacts', [stages, listSig, [...reviewing], App.artifactSel.stageId, selectedKey], () => {
    const container = $('art-list');
    preservingFocus(container, () => mount(container, V.renderArtifactList({
      stages, artifacts: App.artifacts, selectedStage: App.artifactSel.stageId, selectedKey, reviewing,
      onSelect: (stageId, version) => selectArtifact(stageId, version),
    })));
  });
  const stage = asList(App.run?.stages).find((s) => s?.id === App.artifactSel.stageId)
    ?? stages.find((s) => s.id === App.artifactSel.stageId) ?? null;
  const state = summary ? App.artifactViews.get(viewKey(summary)) : null;
  const underReview = Boolean(summary && reviewing.get(summary.stageId) === summary.version);
  renderIfChanged('tab:artifacts:detail', [stage, summary, state?.status, state?.view?.accepted, state?.view?.signatureVerified, underReview], () => mount($('art-detail'), V.renderArtifactDetail({
    stage, summary, state, underReview, open: App.detailsOpen,
    onToggle: (name, open) => { App.detailsOpen[name] = open; },
  })));
}

const auditHandlers = {
  onToggleType(type) {
    if (App.audit.filter.has(type)) App.audit.filter.delete(type);
    else App.audit.filter.add(type);
    renderAuditTab();
  },
  onClearFilter() {
    App.audit.filter.clear();
    renderAuditTab();
  },
  onFollow(on) {
    App.audit.follow = on;
    renderAuditTab();
  },
  onToggleData(seq, open) {
    if (open) App.audit.open.add(seq);
    else App.audit.open.delete(seq);
  },
};

function renderAuditTab() {
  const panel = $('panel-audit');
  if (!App.selectedId) {
    renderIfChanged('tab:audit', 'none', () => mount(panel, V.placeholder('Select a run to see its audit trail.')));
    return;
  }
  const audit = App.audit;
  renderIfChanged('tab:audit', [audit.lastSeq, audit.events.length, [...audit.filter], audit.follow, audit.integrity], () => {
    const before = panel.querySelector('.timeline');
    const keep = before && !audit.follow ? { top: before.scrollTop, height: before.scrollHeight } : null;
    preservingFocus(panel, () => mount(panel, V.renderAudit(audit, auditHandlers)));
    const after = panel.querySelector('.timeline');
    // Without auto-follow, keep the reader on the same events while newer ones are inserted on top.
    if (after && keep) after.scrollTop = keep.top + (after.scrollHeight - keep.height);
  });
}

function renderLineageTab() {
  const panel = $('panel-lineage');
  if (!App.selectedId) {
    renderIfChanged('tab:lineage', 'none', () => mount(panel, V.placeholder('Select a run to see its artifact lineage.')));
    return;
  }
  const stages = orderedStages();
  const layerOf = new Map(asList(App.graph?.nodes).filter((n) => n?.id && Number.isInteger(n.layer)).map((n) => [n.id, n.layer]));
  const stageOrder = new Map(stages.map((s, i) => [s.id, i]));
  const summary = selectedSummary();
  const selectedKey = summary ? `${summary.stageId}|${summary.version}` : null;
  renderIfChanged('tab:lineage', [App.lineage, [...layerOf], selectedKey], () => {
    const graph = renderLineage(App.lineage, {
      layerOf, stageOrder, selectedKey,
      onSelect: (node) => selectArtifact(node.stageId, node.version, { showTab: false }),
    });
    preservingFocus(panel, () => mount(panel,
      h('div', { class: 'card-head' },
        h('p', { class: 'muted small' }, 'Each box is one artifact version; edges run from an input to the artifact derived from it. Faded boxes are superseded versions.'),
        summary ? h('button', { type: 'button', class: 'btn small', onclick: () => setTab('artifacts') }, `Open ${summary.stageId} v${summary.version ?? '?'}`) : null),
      h('div', { class: 'graph-wrap' }, graph ?? V.placeholder(App.lineage ? 'No artifacts yet.' : 'Loading lineage\u2026'))));
  });
}

function renderMetricsTab() {
  renderIfChanged('tab:metrics', [App.metrics, App.metricsAt], () => mount($('panel-metrics'), V.renderMetrics(App.metrics, App.metricsAt)));
}

function renderPolicyTab() {
  const run = App.run;
  renderIfChanged('tab:policy', [App.selectedId, run?.policyFindings ?? null, App.policy], () => mount($('panel-policy'),
    V.renderPolicyInForce(App.policy, {
      open: App.detailsOpen.policyInForce,
      onToggle: (open) => { App.detailsOpen.policyInForce = open; },
    }),
    !App.selectedId ? V.placeholder('Select a run to see its policy findings.')
      : run ? V.renderPolicy(run.policyFindings) : V.placeholder('Loading\u2026')));
}

function renderEvaluationTab() {
  const panel = $('panel-evaluation');
  if (!App.selectedId) {
    renderIfChanged('tab:evaluation', 'none', () => mount(panel, V.placeholder('Select a run to see the evaluation of its artifacts.')));
    return;
  }
  const { status, report, error } = App.evaluation;
  renderIfChanged('tab:evaluation', [status, report, error?.detail ?? error?.title ?? null], () => mount(panel,
    V.renderEvaluation({ status, report, error, onRefresh: () => refreshEvaluation({ force: true }) })));
}

// ---------------------------------------------------------------- wiring

function setTab(tab, { focus = false } = {}) {
  App.tab = TABS.includes(tab) ? tab : 'artifacts';
  store.set('sdlc.tab', App.tab);
  for (const name of TABS) {
    const selected = name === App.tab;
    const button = $(`tab-${name}`);
    button.setAttribute('aria-selected', String(selected));
    button.tabIndex = selected ? 0 : -1;
    $(`panel-${name}`).hidden = !selected;
  }
  if (focus) $(`tab-${App.tab}`).focus();
  if (App.tab === 'metrics') loops.metrics?.kick();
  if (App.tab === 'evaluation') refreshEvaluation();
  if (App.tab === 'lineage' || App.tab === 'audit') loops.run?.kick();
  renderActiveTab();
}

function initTabs() {
  const tablist = $('tablist');
  tablist.addEventListener('click', (e) => {
    const tab = e.target.closest('[role="tab"]');
    if (tab) setTab(tab.dataset.tab);
  });
  tablist.addEventListener('keydown', (e) => {
    const index = TABS.indexOf(App.tab);
    const target = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: TABS.length - 1 }[e.key];
    if (target === undefined) return;
    e.preventDefault();
    setTab(TABS[(target + TABS.length) % TABS.length], { focus: true });
  });
  setTab(App.tab);
}

function initHeader() {
  const input = $('operator-id');
  input.value = App.operatorId;
  input.addEventListener('input', () => {
    App.operatorId = input.value.trim();
    input.setAttribute('aria-invalid', String(App.operatorId !== '' && !OPERATOR_ID.test(App.operatorId)));
    store.set('sdlc.operatorId', App.operatorId);
  });
}

function setSelect(id, value, fallback) {
  const select = $(id);
  select.value = [...select.options].some((o) => o.value === value) ? value : fallback;
}

function applyScenario(key) {
  const scenario = asList(App.scenarios).find((s) => s.key === key) ?? null;
  $('nr-scenario-run').disabled = !scenario;
  $('nr-description').textContent = scenario?.description ?? '';
  $('nr-description').hidden = !scenario?.description;
  const steps = asList(scenario?.walkthrough);
  mount($('nr-walkthrough'), steps.map((step) => h('li', {}, String(step))));
  $('nr-walkthrough-wrap').hidden = !steps.length;
  if (!scenario) return;
  $('nr-requirement').value = scenario.requirement ?? '';
  setSelect('nr-autonomy', scenario.autonomy, 'GUIDED');
  setSelect('nr-workspace', scenario.workspace, 'REPO');
  const fault = asList(scenario.faults)[0];
  setSelect('nr-fault-stage', fault?.stageId ?? '', '');
  $('nr-fault-times').value = String(fault?.failTimes ?? 1);
  setSelect('nr-fault-kind', fault?.kind, 'TRANSIENT');
  updateCharCount();
}

function updateCharCount() {
  $('nr-count').textContent = String($('nr-requirement').value.length);
}

function buildCreateRunRequest() {
  const requirement = $('nr-requirement').value.trim();
  if (requirement.length < 10 || requirement.length > 4000) {
    toast('error', 'Requirement length', 'The requirement needs 10 to 4000 characters.');
    $('nr-requirement').focus();
    return null;
  }
  const faultStage = $('nr-fault-stage').value;
  const failTimes = Math.min(99, Math.max(1, Number.parseInt($('nr-fault-times').value, 10) || 1));
  return {
    requirement,
    scenario: $('nr-scenario').value || 'custom',
    autonomy: $('nr-autonomy').value,
    provider: $('nr-provider').value || undefined, // omitted -> server default provider
    faults: faultStage ? [{ stageId: faultStage, failTimes, kind: $('nr-fault-kind').value }] : [],
    workspace: $('nr-workspace').value,
  };
}

async function submitRun(fromCatalog) {
  if (!requireOperator()) return;
  const scenario = $('nr-scenario').value;
  const provider = $('nr-provider').value;
  let request;
  if (fromCatalog) {
    if (!scenario) return;
    request = () => api(`/api/v1/scenarios/${enc(scenario)}/run${provider ? `?provider=${enc(provider)}` : ''}`,
      { method: 'POST', operatorId: App.operatorId, timeoutMs: 30_000 });
  } else {
    const body = buildCreateRunRequest();
    if (!body) return;
    request = () => api('/api/v1/runs', { method: 'POST', body, operatorId: App.operatorId, timeoutMs: 30_000 });
  }
  const [start, runScenario] = [$('nr-start'), $('nr-scenario-run')];
  const trigger = fromCatalog ? runScenario : start;
  start.disabled = runScenario.disabled = true;
  trigger.setAttribute('aria-busy', 'true');
  try {
    const run = await request();
    toast('success', `Run ${shortId(run?.id)} started`, run?.status ? `Status: ${humanize(run.status)}` : '');
    if (run?.id) {
      selectRun(run.id);
      applyRun(run);
      renderRunView();
    }
  } catch (err) {
    toastError(err);
  } finally {
    trigger.removeAttribute('aria-busy');
    start.disabled = false;
    runScenario.disabled = !$('nr-scenario').value;
    loops.runs?.kick();
  }
}

function initNewRunForm() {
  mount($('nr-fault-stage'), h('option', { value: '' }, 'none'), FAULT_STAGES.map((id) => h('option', { value: id }, id)));
  $('nr-scenario').addEventListener('change', (e) => applyScenario(e.target.value));
  $('nr-requirement').addEventListener('input', updateCharCount);
  $('new-run-form').addEventListener('submit', (e) => {
    e.preventDefault();
    submitRun(false);
  });
  $('nr-scenario-run').addEventListener('click', () => submitRun(true));
  applyScenario('');
  updateCharCount();
}

function initActionsCard() {
  // Inputs inside the card are re-created on re-render; their values live in App.drafts.
  const sync = (e) => {
    const key = e.target?.dataset?.draft;
    if (key) App.drafts[key] = e.target.value;
  };
  $('actions-card').addEventListener('input', sync);
  $('actions-card').addEventListener('change', sync);
}

function boot() {
  initHeader();
  initNewRunForm();
  initActionsCard();
  mount($('graph-legend'), renderLegend());
  initTabs();
  window.addEventListener('hashchange', () => selectRun(runIdFromHash(), { fromHash: true }));
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) Object.values(loops).forEach((l) => l.kick());
  });
  loops.catalog = loop(refreshCatalog, () => (App.scenarios ? POLL_MS.catalog : POLL_MS.catalogRetry));
  loops.runs = loop(refreshRuns, runsPollInterval);
  loops.run = loop(refreshSelectedRun, runPollInterval);
  loops.metrics = loop(refreshMetrics, () => POLL_MS.metrics);
  startListStream();
  selectRun(runIdFromHash(), { fromHash: true });
  renderRunView();
  renderRunsList();
  renderStreamState();
}

boot();
