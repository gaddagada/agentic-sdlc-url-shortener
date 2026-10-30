// Pure render functions: API data in, DOM nodes out. No fetching and no global
// state; app.js passes in the data, the form drafts and the callbacks.
import {
  h, chip, statusPill, statusToken, humanize, shortId, isNum, asList, pretty, DASH,
  fmtMs, fmtNum, fmtCompact, fmtPct, fmtRatio, fmtTime, fmtWhen, fmtDateTime,
} from './util.js';
import { renderMarkdown } from './markdown.js';

export const placeholder = (text) => h('p', { class: 'placeholder' }, text);
const facts = (rows) =>
  h('dl', { class: 'facts' }, rows.map(([label, value]) => h('div', {}, h('dt', {}, label), h('dd', {}, value ?? DASH))));

// ---------------------------------------------------------------- runs list

export function renderRunsList(runs, selectedId, onSelect) {
  if (!runs.length) return h('li', {}, placeholder('No runs yet. Start one above.'));
  return runs.map((run) => {
    const { succeeded, total } = run.progress ?? {};
    const pending = Array.isArray(run.pendingActions) ? run.pendingActions.length : Number(run.pendingActions) || 0;
    const selected = run.id === selectedId;
    const pct = isNum(total) && total > 0 && isNum(succeeded) ? Math.min(100, (succeeded / total) * 100) : 0;
    return h('li', {},
      h('button', { type: 'button', class: `run-item${selected ? ' selected' : ''}`, 'aria-current': selected ? 'true' : null, 'data-fk': `run:${run.id}`, onclick: () => onSelect(run.id) },
        h('span', { class: 'run-line' },
          statusPill(run.status),
          h('span', { class: 'run-scenario' }, run.scenario || 'custom'),
          pending ? h('span', { class: 'badge', title: 'Pending human actions' }, `${pending} pending`) : null),
        h('span', { class: 'run-req' }, run.requirement || DASH),
        h('span', { class: 'run-line meta' },
          h('span', { class: 'mono', title: run.id }, shortId(run.id)),
          h('span', { title: fmtDateTime(run.createdAt) }, fmtWhen(run.createdAt)),
          run.haltReason ? h('span', { class: 'halt-text' }, humanize(run.haltReason)) : null,
          h('span', { class: 'run-progress', title: 'Stages succeeded / total' }, `${isNum(succeeded) ? succeeded : DASH}/${isNum(total) ? total : DASH}`)),
        h('span', { class: 'meter-track', 'aria-hidden': 'true' }, h('span', { class: 'meter-fill', style: { width: `${pct}%` } }))));
  });
}

// ---------------------------------------------------------------- status banner

const stat = (label, value, title = null) => h('div', { class: 'stat', title }, h('dt', {}, label), h('dd', {}, value));
const outOf = (used, max) => (isNum(max) ? `${fmtNum(used)} / ${fmtNum(max)}` : fmtNum(used));

function budgetMeter(label, used, max) {
  const ratio = isNum(used) && isNum(max) && max > 0 ? used / max : null;
  const level = ratio == null ? '' : ratio >= 1 ? ' danger' : ratio >= 0.8 ? ' warn' : '';
  return h('div', { class: 'stat' },
    h('dt', {}, label),
    h('dd', {}, `${fmtCompact(used)} / ${fmtCompact(max)}`),
    h('dd', { class: `meter-track${level}`, 'aria-hidden': 'true' }, h('span', { class: 'meter-fill', style: { width: `${Math.min(100, (ratio ?? 0) * 100)}%` } })));
}

export function renderBanner(run) {
  const timing = run.timing ?? {};
  const budget = run.budget ?? {};
  const counters = run.counters ?? {};
  const risk = run.risk ?? {};
  const replanned = run.activeRequirement && run.requirement && run.activeRequirement !== run.requirement;
  return [
    h('div', { class: 'banner-head' },
      statusPill(run.status, 'pill-lg'),
      h('h2', { class: 'banner-title' }, run.scenario || 'custom', ' ', h('span', { class: 'mono muted', title: run.id }, `#${shortId(run.id)}`)),
      h('div', { class: 'chips' },
        chip(`plan v${run.planVersion ?? DASH}`, '', 'Plan version: incremented by every replan'),
        chip(run.autonomy && humanize(run.autonomy), '', 'Autonomy level'),
        chip(run.provider, 'mono', 'Agent provider'),
        chip(run.workspace && `workspace ${run.workspace}`),
        risk.level ? chip(`risk ${humanize(risk.level)}`, `risk-${statusToken(risk.level)}`, asList(risk.factors).join('\n') || null) : null)),
    run.haltReason
      ? h('p', { class: 'halt', role: 'note' }, h('strong', {}, `Halted: ${humanize(run.haltReason)}`), run.haltDetail ? ` \u2014 ${run.haltDetail}` : '')
      : null,
    h('p', { class: 'requirement' },
      replanned ? chip('replanned', '', `Original requirement: ${run.requirement}`) : null, ' ',
      run.activeRequirement || run.requirement || DASH),
    h('dl', { class: 'stats' },
      stat('End-to-end', fmtMs(timing.endToEndMs), `Started ${fmtDateTime(run.startedAt)}\nEnded ${fmtDateTime(run.endedAt)}`),
      stat('Active', fmtMs(timing.activeMs)),
      stat('Human wait', fmtMs(timing.humanWaitMs)),
      budgetMeter('Agent calls', budget.agentCallsUsed, budget.maxAgentCalls),
      budgetMeter('LLM tokens', budget.llmTokensUsed, budget.maxLlmTokens),
      stat('Retries', fmtNum(counters.retries)),
      stat('Fallbacks', fmtNum(counters.fallbacks)),
      stat('Rollbacks', fmtNum(counters.rollbacks)),
      stat('Replans', outOf(counters.replans, budget.maxReplans)),
      stat('Feedback loops', outOf(counters.feedbackLoops, budget.maxFeedbackLoops)),
      stat('Approvals', `${fmtNum(counters.approvalsGranted)} / ${fmtNum(counters.approvalsRequested)}`, 'Granted / requested'),
      isNum(timing.mttrMs) ? stat('MTTR', fmtMs(timing.mttrMs)) : null),
  ];
}

// ---------------------------------------------------------------- pending actions + run controls

const ACTIVE = ['RUNNING', 'WAITING_HUMAN'];
const textInput = (key, draft, attrs) =>
  h('input', { type: 'text', 'data-draft': key, 'data-fk': key, value: draft(key), maxlength: 2000, ...attrs });

function approvalAction(action, draft, stageName, on) {
  const stage = stageName(action.stageId);
  const key = (field) => `${field}:${action.stageId}`;
  const disposition = draft(key('disposition'), 'REWORK');
  return h('article', { class: 'action approval' },
    h('header', { class: 'action-head' },
      h('h3', {}, `Approval required: ${stage}`),
      chip(action.gateKind && `${humanize(action.gateKind)} gate`),
      action.requestedAt ? h('time', { class: 'muted small', datetime: action.requestedAt, title: fmtDateTime(action.requestedAt) }, `requested ${fmtTime(action.requestedAt)}`) : null),
    action.reason ? h('p', { class: 'action-reason' }, action.reason) : null,
    h('div', { class: 'action-row' },
      textInput(key('comment'), draft, { placeholder: 'Comment (optional)', 'aria-label': `Approval comment for ${stage}` }),
      h('button', { type: 'button', class: 'btn ok', onclick: () => on.approve(action.stageId) }, 'Approve')),
    h('div', { class: 'action-row' },
      textInput(key('reason'), draft, { placeholder: 'Rejection reason (required)', 'aria-label': `Rejection reason for ${stage}` }),
      h('select', { 'aria-label': `Rejection disposition for ${stage}`, 'data-draft': key('disposition'), 'data-fk': key('disposition') },
        h('option', { value: 'REWORK', selected: disposition === 'REWORK' }, 'Rework'),
        h('option', { value: 'ABORT', selected: disposition === 'ABORT' }, 'Abort run')),
      h('button', { type: 'button', class: 'btn danger', onclick: () => on.reject(action.stageId) }, 'Reject')));
}

function clarificationAction(action, draft, stageName, on) {
  const questions = asList(action.questions).filter(Boolean).map((q, i) => ({ ...q, id: q.id ?? `q${i + 1}` }));
  return h('article', { class: 'action clarification' },
    h('header', { class: 'action-head' }, h('h3', {}, 'Clarification needed'), chip(stageName(action.stageId ?? 'intake'))),
    h('p', { class: 'muted small' }, 'The requirement is ambiguous. Answer every question so intake can continue.'),
    questions.map((q) => {
      const key = `clar:${q.id}`;
      const inputId = `clar-${String(q.id).replace(/[^\w-]/g, '_')}`;
      const suggestions = asList(q.suggestedAnswers).map(String);
      return h('div', { class: 'question' },
        h('label', { for: inputId }, q.question || q.id),
        q.why ? h('p', { class: 'why' }, q.why) : null,
        h('textarea', { id: inputId, rows: 2, maxlength: 2000, 'data-draft': key, 'data-fk': key, value: draft(key) }),
        suggestions.length
          ? h('div', { class: 'suggestions', role: 'group', 'aria-label': 'Suggested answers' },
            suggestions.map((s) => h('button', { type: 'button', class: 'suggestion', onclick: () => on.fill(key, s) }, s)))
          : null);
    }),
    h('div', { class: 'action-row end' },
      h('button', { type: 'button', class: 'btn primary', onclick: () => on.clarify(questions.map((q) => q.id)) }, 'Submit answers')));
}

function runControls(run, draft, on) {
  const budget = run.budget ?? {};
  const counters = run.counters ?? {};
  const replansLeft = !(isNum(counters.replans) && isNum(budget.maxReplans) && counters.replans >= budget.maxReplans);
  const canReplan = [...ACTIVE, 'SAFE_STOPPED'].includes(run.status) && replansLeft;
  const canStop = ACTIVE.includes(run.status);
  const policyHalt = run.status === 'SAFE_STOPPED' && run.haltReason === 'POLICY_DENIED';
  const canResume = run.status === 'SAFE_STOPPED' && !policyHalt;
  return h('div', { class: 'controls' },
    h('div', { class: 'action-row' },
      h('h3', {}, 'Run controls'),
      textInput('stop', draft, { placeholder: 'Stop reason (optional)', 'aria-label': 'Stop reason', class: 'grow' }),
      h('button', { type: 'button', class: 'btn danger-outline', disabled: !canStop, onclick: on.stop, title: canStop ? null : 'Only running or waiting runs can be stopped' }, 'Stop'),
      h('button', { type: 'button', class: 'btn', disabled: !canResume, onclick: on.resume, title: canResume ? null : 'Only safe-stopped runs (not policy denials) can be resumed' }, 'Resume')),
    policyHalt ? h('p', { class: 'hint' }, 'A policy denial cannot be resumed: change the requirement and use Replan.') : null,
    // A policy denial can only be fixed by a replan, so that case opens the section by default.
    h('details', { class: 'replan', open: draft('replan:open', String(policyHalt)) === 'true', ontoggle: (e) => on.toggleReplan(e.currentTarget.open) },
      h('summary', {}, 'Replan with a changed requirement'),
      h('textarea', { 'aria-label': 'Changed requirement', rows: 3, maxlength: 4000, 'data-draft': 'replan', 'data-fk': 'replan', value: draft('replan', run.activeRequirement ?? run.requirement ?? '') }),
      h('div', { class: 'action-row end' },
        h('span', { class: 'hint' }, 'Invalidates the affected stages, revokes their approvals and re-runs them.'),
        h('button', {
          type: 'button', class: 'btn', disabled: !canReplan, onclick: on.replan,
          title: canReplan ? null : replansLeft ? 'Replan is possible while running, waiting or safe-stopped' : 'Replan budget exhausted',
        }, 'Replan'))));
}

export function renderActions(run, { drafts, busy, stageName, on }) {
  const draft = (key, fallback = '') => drafts[key] ?? fallback;
  const pending = asList(run.pendingActions).filter(Boolean);
  return [
    h('div', { class: 'card-head' },
      h('h2', {}, 'Pending actions'),
      pending.length ? h('span', { class: 'badge' }, `${pending.length} waiting`) : null),
    h('fieldset', { class: 'actions', disabled: busy, 'aria-busy': busy ? 'true' : null },
      h('legend', { class: 'sr-only' }, 'Governance actions'),
      pending.length
        ? pending.map((a) => (a.type === 'CLARIFICATION' ? clarificationAction(a, draft, stageName, on) : approvalAction(a, draft, stageName, on)))
        : placeholder('No human action required right now.'),
      runControls(run, draft, on)),
  ];
}

// ---------------------------------------------------------------- artifacts

export function renderArtifactList({ stages, artifacts, selectedStage, selectedKey, onSelect }) {
  const byStage = new Map();
  for (const a of artifacts) (byStage.get(a.stageId) ?? byStage.set(a.stageId, []).get(a.stageId)).push(a);
  const rows = [...stages];
  for (const id of byStage.keys()) if (!rows.some((s) => s.id === id)) rows.push({ id, name: id });
  if (!rows.length) return placeholder('No stages yet.');
  return h('ul', { class: 'art-stages' }, rows.map((stage) => {
    const versions = (byStage.get(stage.id) ?? []).slice().sort((x, y) => (x.version ?? 0) - (y.version ?? 0));
    const current = stage.id === selectedStage;
    return h('li', { class: `art-stage${current ? ' current' : ''}` },
      h('button', { type: 'button', class: 'art-stage-btn', 'aria-current': current ? 'true' : null, 'data-fk': `stage:${stage.id}`, onclick: () => onSelect(stage.id, null) },
        h('span', { class: `dot st-${statusToken(stage.status)}`, 'aria-hidden': 'true' }),
        h('span', { class: 'art-stage-name' }, stage.name || stage.id),
        h('span', { class: 'sr-only' }, `, ${humanize(stage.status)}`)),
      h('span', { class: 'versions' }, versions.length
        ? versions.map((a) => {
          const selected = selectedKey === `${a.stageId}|${a.version}`;
          return h('button', {
            type: 'button', class: `vchip${a.accepted ? ' accepted' : ''}${selected ? ' selected' : ''}`,
            'aria-pressed': selected ? 'true' : 'false', 'data-fk': `ver:${a.stageId}:${a.version}`,
            title: `${humanize(a.kind)} v${a.version} \u2014 ${a.accepted ? 'accepted' : 'superseded'}${a.hash ? `\n${a.hash}` : ''}`,
            onclick: () => onSelect(a.stageId, a.version),
          }, `v${a.version ?? '?'}`);
        })
        : h('span', { class: 'muted small' }, 'none')));
  }));
}

function renderProvenance(p) {
  if (!p || typeof p !== 'object') return placeholder('No provenance recorded.');
  const derived = p.derivedFrom && typeof p.derivedFrom === 'object' ? Object.entries(p.derivedFrom) : [];
  const tokens = p.inputTokens != null || p.outputTokens != null ? `${fmtNum(p.inputTokens)} / ${fmtNum(p.outputTokens)}` : null;
  return [
    facts([
      ['Agent role', p.agentRole], ['Provider', p.providerId], ['Model', p.model], ['Attempt', p.attempt != null ? String(p.attempt) : null],
      ['Latency', isNum(p.latencyMs) ? fmtMs(p.latencyMs) : null], ['Tokens in / out', tokens], ['Prompt hash', p.promptHash],
    ]),
    h('p', { class: 'derived' }, h('span', { class: 'muted' }, 'Derived from: '),
      derived.length ? derived.map(([stage, version]) => chip(`${stage} v${version}`, 'mono')) : 'no upstream artifacts (root)'),
    h('pre', { class: 'json' }, pretty(p)),
  ];
}

function artifactBody(summary, state, open, onToggle) {
  if (!summary) return placeholder('This stage has not produced an artifact yet.');
  if (!state || state.status === 'loading') return placeholder('Loading artifact\u2026');
  if (state.status === 'error') {
    return placeholder(`Could not load this artifact (${state.error?.detail || state.error?.title || 'error'}). Click its version chip to retry.`);
  }
  const view = state.view ?? {};
  const markdown = h('div', { class: 'markdown' });
  // The only innerHTML sink in the console: renderMarkdown HTML-escapes its input before adding markup.
  if (view.markdown) markdown.innerHTML = renderMarkdown(view.markdown);
  else markdown.append(placeholder('No Markdown rendering for this artifact; see the payload below.'));
  const section = (name, title, content, defaultOpen = false) =>
    h('details', { class: 'raw', open: open[name] || defaultOpen, ontoggle: (e) => onToggle(name, e.currentTarget.open) }, h('summary', {}, title), content);
  return [
    markdown,
    section('payload', 'Payload JSON', h('pre', { class: 'json' }, pretty(view.payload)), !view.markdown),
    section('provenance', 'Provenance', renderProvenance(view.provenance ?? summary.provenance)),
  ];
}

export function renderArtifactDetail({ stage, summary, state, open, onToggle }) {
  if (!stage && !summary) return placeholder('Select a stage or a version chip, or click a node in the pipeline graph.');
  const s = stage ?? {};
  const art = state?.view ?? summary;
  return [
    h('header', { class: 'art-head' },
      h('h3', {}, s.name || s.id || summary?.stageId),
      art ? chip(`v${art.version ?? '?'}`, 'mono') : null,
      art?.kind ? chip(humanize(art.kind)) : null,
      typeof art?.accepted === 'boolean' ? chip(art.accepted ? 'accepted' : 'superseded', art.accepted ? 'chip-ok' : 'chip-muted') : null,
      art?.hash ? h('span', { class: 'mono muted small', title: art.hash }, `#${String(art.hash).slice(0, 12)}`) : null,
      art?.createdAt ? h('span', { class: 'muted small', title: fmtDateTime(art.createdAt) }, fmtWhen(art.createdAt)) : null),
    stage ? facts([['Status', statusPill(s.status)], ['Attempts', fmtNum(s.attempts)], ['Agent', s.activeAgent || DASH], ['Duration', fmtMs(s.durationMs)]]) : null,
    s.lastError ? h('p', { class: 'error-box' }, h('strong', {}, 'Last error: '), s.lastError) : null,
    asList(s.feedback).length
      ? h('div', { class: 'feedback' }, h('h4', {}, 'Feedback for this stage'), h('ul', {}, asList(s.feedback).map((f) => h('li', {}, String(f)))))
      : null,
    artifactBody(summary, state, open, onToggle),
  ];
}

// ---------------------------------------------------------------- audit

const EVENT_TONES = [
  [/FAIL|DENIED|EXCEEDED|ABORTED|SAFE_STOP|REJECTED|ROLLBACK|INTERRUPTED/, 'bad'],
  [/SUCCEEDED|COMPLETED|ACCEPTED|GRANTED|PROVIDED|RESUMED/, 'ok'],
  [/APPROVAL|CLARIFICATION/, 'human'],
  [/RETRY|FALLBACK|FEEDBACK|REPLAN|INVALIDATED|POLICY_FINDING|COMPENSATION/, 'warn'],
];
const toneOf = (type) => EVENT_TONES.find(([re]) => re.test(type ?? ''))?.[1] ?? 'neutral';
const AUDIT_RENDER_CAP = 400;

function auditEvent(event, openSet, onToggleData) {
  const actor = event.actor ?? {};
  const data = event.data && typeof event.data === 'object' && Object.keys(event.data).length ? event.data : null;
  return h('li', { class: `event tone-${toneOf(event.type)}` },
    h('div', { class: 'event-line' },
      h('time', { datetime: event.timestamp, title: fmtDateTime(event.timestamp) }, fmtTime(event.timestamp)),
      h('span', { class: 'mono muted small' }, `#${event.seq ?? '?'}`),
      h('span', { class: 'etype' }, humanize(event.type)),
      event.stageId ? chip(event.stageId, 'mono') : null,
      h('span', { class: `actor actor-${statusToken(actor.type)}` }, actor.type ?? 'UNKNOWN', actor.id ? h('span', { class: 'actor-id' }, ` ${actor.id}`) : null)),
    event.message ? h('p', { class: 'event-msg' }, event.message) : null,
    data
      ? h('details', { class: 'event-data', open: openSet.has(event.seq), ontoggle: (e) => onToggleData(event.seq, e.currentTarget.open) },
        h('summary', {}, 'data'), h('pre', { class: 'json' }, pretty(data)))
      : null);
}

export function renderAudit({ events, filter, follow, open }, { onToggleType, onClearFilter, onFollow, onToggleData }) {
  const counts = new Map();
  for (const e of events) counts.set(e.type, (counts.get(e.type) ?? 0) + 1);
  const shown = events.filter((e) => !filter.size || filter.has(e.type));
  const newestFirst = shown.slice(-AUDIT_RENDER_CAP).reverse();
  return [
    h('div', { class: 'audit-toolbar' },
      h('div', { class: 'filters', role: 'group', 'aria-label': 'Filter events by type' },
        h('button', { type: 'button', class: `filter${filter.size ? '' : ' on'}`, 'aria-pressed': filter.size ? 'false' : 'true', 'data-fk': 'filter:*', onclick: onClearFilter }, `All (${events.length})`),
        [...counts.keys()].sort().map((type) => h('button', {
          type: 'button', class: `filter tone-${toneOf(type)}${filter.has(type) ? ' on' : ''}`,
          'aria-pressed': filter.has(type) ? 'true' : 'false', 'data-fk': `filter:${type}`, onclick: () => onToggleType(type),
        }, `${humanize(type)} (${counts.get(type)})`))),
      h('label', { class: 'toggle' },
        h('input', { type: 'checkbox', checked: follow, 'data-fk': 'follow', onchange: (e) => onFollow(e.currentTarget.checked) }), 'Auto-follow newest')),
    newestFirst.length
      ? h('ol', { class: 'timeline', 'aria-label': 'Audit events, newest first' }, newestFirst.map((e) => auditEvent(e, open, onToggleData)))
      : placeholder(events.length ? 'No events match the selected types.' : 'No audit events yet.'),
    shown.length > AUDIT_RENDER_CAP ? h('p', { class: 'muted small' }, `Showing the newest ${AUDIT_RENDER_CAP} of ${shown.length} events.`) : null,
  ];
}

// ---------------------------------------------------------------- metrics

const tile = (label, value, sub, extra = null, wide = false) =>
  h('div', { class: `tile${wide ? ' wide' : ''}` },
    h('div', { class: 'tile-label' }, label), h('div', { class: 'tile-value' }, value), sub ? h('div', { class: 'tile-sub' }, sub) : null, extra);

/** Proportional bar with a labelled legend, so identity never relies on colour alone. */
function segments(parts, format = fmtNum) {
  const total = parts.reduce((sum, p) => sum + (isNum(p.value) && p.value > 0 ? p.value : 0), 0);
  return [
    h('div', { class: 'segbar', role: 'img', 'aria-label': parts.map((p) => `${p.label} ${format(p.value)}`).join(', ') },
      total ? parts.filter((p) => isNum(p.value) && p.value > 0).map((p) =>
        h('span', { class: `seg st-${p.token}`, title: `${p.label}: ${format(p.value)}`, style: { flexGrow: String(p.value) } })) : null),
    h('ul', { class: 'seglegend' }, parts.map((p) =>
      h('li', {}, h('span', { class: `swatch st-${p.token}`, 'aria-hidden': 'true' }), `${p.label} `, h('strong', {}, format(p.value))))),
  ];
}

export function renderMetrics(m, fetchedAt) {
  if (!m || typeof m !== 'object') return placeholder('Loading reliability metrics\u2026');
  // `?? {}` rather than destructuring defaults: defaults do not apply to explicit nulls.
  const [runs, stages, latency, recovery, approvals, llm] = ['runs', 'stages', 'latency', 'recovery', 'approvals', 'llm'].map((k) => m[k] ?? {});
  const tokens = isNum(llm.inputTokens) || isNum(llm.outputTokens) ? (llm.inputTokens ?? 0) + (llm.outputTokens ?? 0) : null;
  return [
    h('p', { class: 'muted small' }, `All runs \u00b7 refreshed every 5 s \u00b7 last update ${fetchedAt ? fmtTime(fetchedAt) : DASH}`),
    h('div', { class: 'tiles' },
      tile('Success rate', fmtPct(runs.successRate), `${fmtNum(runs.completed)} completed, ${fmtNum(runs.aborted)} aborted`),
      tile('Runs by status', fmtNum(runs.total), null, segments([
        { label: 'Completed', value: runs.completed, token: 'COMPLETED' },
        { label: 'In progress', value: runs.inProgress, token: 'RUNNING' },
        { label: 'Waiting human', value: runs.waitingHuman, token: 'WAITING_HUMAN' },
        { label: 'Safe-stopped', value: runs.safeStopped, token: 'SAFE_STOPPED' },
        { label: 'Aborted', value: runs.aborted, token: 'ABORTED' },
      ]), true),
      tile('Retries', fmtNum(stages.retries), `${fmtPct(stages.retryRate)} of ${fmtNum(stages.executions)} stage executions`),
      tile('Fallbacks', fmtNum(stages.fallbacks), 'Deterministic agent took over'),
      tile('Rollbacks', fmtNum(stages.rollbacks), `${fmtRatio(stages.rollbackFrequency)} per run`),
      tile('Feedback loops', fmtNum(stages.feedbackLoops), 'Verify sent implement back'),
      tile('Replans', fmtNum(stages.replans), 'Requirement changed mid-run'),
      tile('MTTR', fmtMs(recovery.mttrMs), `${fmtNum(recovery.recovered)} of ${fmtNum(recovery.incidents)} incidents recovered, ${fmtNum(recovery.unrecovered)} open`),
      tile('End-to-end p50 / p95', `${fmtMs(latency.endToEndMsP50)} / ${fmtMs(latency.endToEndMsP95)}`, `Average ${fmtMs(latency.endToEndMsAvg)} (completed runs)`),
      tile('Active vs human wait', fmtMs(latency.activeMsAvg), 'Average per completed run', segments([
        { label: 'Active', value: latency.activeMsAvg, token: 'RUNNING' },
        { label: 'Human wait', value: latency.humanWaitMsAvg, token: 'WAITING_HUMAN' },
      ], fmtMs)),
      tile('Agent call avg / p95', `${fmtMs(latency.agentCallMsAvg)} / ${fmtMs(latency.agentCallMsP95)}`, 'From artifact provenance'),
      tile('Approvals', `${fmtNum(approvals.approved)} / ${fmtNum(approvals.requested)}`,
        `${fmtNum(approvals.rejected)} rejected, ${fmtNum(approvals.revoked)} revoked, mean decision ${fmtMs(approvals.meanDecisionMs)}`),
      tile('LLM tokens', fmtCompact(tokens), `${fmtNum(llm.calls)} calls, ${fmtCompact(llm.inputTokens)} in / ${fmtCompact(llm.outputTokens)} out`)),
  ];
}

// ---------------------------------------------------------------- policy

const VERDICT_STATUS = { DENY: 'FAILED', REQUIRE_APPROVAL: 'AWAITING_APPROVAL', ALLOW: 'SUCCEEDED' };

export function renderPolicy(findings) {
  const rows = asList(findings).filter(Boolean);
  if (!rows.length) return placeholder('No policy findings for this run.');
  return h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
    h('thead', {}, h('tr', {}, ['Time', 'Stage', 'Rule', 'Severity', 'Verdict', 'Message'].map((c) => h('th', { scope: 'col' }, c)))),
    h('tbody', {}, rows.map((f) => h('tr', {},
      h('td', { class: 'nowrap', title: fmtDateTime(f.timestamp) }, fmtTime(f.timestamp)),
      h('td', { class: 'mono' }, f.stageId ?? 'run start'),
      h('td', { class: 'mono nowrap' }, f.ruleId ?? DASH),
      h('td', {}, chip(humanize(f.severity), `sev-${statusToken(f.severity)}`)),
      h('td', {}, h('span', { class: `pill st-${VERDICT_STATUS[f.verdict] ?? 'PENDING'}` }, humanize(f.verdict))),
      h('td', {}, f.message ?? DASH))))));
}
