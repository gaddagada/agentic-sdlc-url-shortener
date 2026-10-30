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
          run.startedBy ? h('span', { class: 'run-starter', title: 'Started by' }, run.startedBy) : null,
          run.haltReason ? h('span', { class: 'halt-text' }, humanize(run.haltReason)) : null,
          h('span', { class: 'run-progress', title: 'Stages succeeded / total' }, `${isNum(succeeded) ? succeeded : DASH}/${isNum(total) ? total : DASH}`)),
        h('span', { class: 'meter-track', 'aria-hidden': 'true' }, h('span', { class: 'meter-fill', style: { width: `${pct}%` } }))));
  });
}

/** Paging and status filter of the runs list. */
export function renderPager({ page, size, status, total, totalPages, loaded, sizes, statuses, on }) {
  const pages = Math.max(1, totalPages || 0);
  const first = total ? page * size + 1 : 0;
  const last = Math.min(total, (page + 1) * size);
  return [
    h('div', { class: 'pager-row' },
      h('label', { class: 'pager-field' }, h('span', {}, 'Status'),
        h('select', { 'data-fk': 'pager:status', onchange: (e) => on.status(e.currentTarget.value) },
          h('option', { value: '', selected: !status }, 'All'),
          statuses.map((s) => h('option', { value: s, selected: s === status }, humanize(s))))),
      h('label', { class: 'pager-field' }, h('span', {}, 'Per page'),
        h('select', { 'data-fk': 'pager:size', onchange: (e) => on.size(e.currentTarget.value) },
          sizes.map((n) => h('option', { value: String(n), selected: n === size }, String(n)))))),
    h('div', { class: 'pager-row' },
      h('button', { type: 'button', class: 'btn small', 'data-fk': 'pager:prev', disabled: page <= 0, onclick: () => on.page(page - 1), 'aria-label': 'Previous page' }, '\u2039 Prev'),
      h('span', { class: 'muted small pager-info', 'aria-live': 'polite' },
        loaded ? (total ? `${fmtNum(first)}\u2013${fmtNum(last)} of ${fmtNum(total)} \u00b7 page ${page + 1}/${pages}` : 'No runs') : 'Loading\u2026'),
      h('button', { type: 'button', class: 'btn small', 'data-fk': 'pager:next', disabled: page + 1 >= pages, onclick: () => on.page(page + 1), 'aria-label': 'Next page' }, 'Next \u203a')),
  ];
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

/** "LLM cost: $x.xx" when every LLM call of the run was priced, else "unpriced (n calls)"; null without LLM calls. */
function llmCostStat(counters) {
  if (!isNum(counters.llmCalls) || counters.llmCalls <= 0) return null;
  const cache = isNum(counters.llmCacheReadTokens) || isNum(counters.llmCacheCreationTokens)
    ? `Prompt cache: ${fmtNum(counters.llmCacheReadTokens ?? 0)} tokens read, ${fmtNum(counters.llmCacheCreationTokens ?? 0)} written`
    : null;
  if (isNum(counters.llmCostUsd)) {
    return stat('LLM cost', fmtUsd(counters.llmCostUsd), [`Estimated from sdlc.llm.pricing over ${fmtNum(counters.llmCalls)} LLM calls`, cache].filter(Boolean).join('\n'));
  }
  const n = isNum(counters.llmUnpricedCalls) ? counters.llmUnpricedCalls : counters.llmCalls;
  return stat('LLM cost', `unpriced (${fmtNum(n)} call${n === 1 ? '' : 's'})`,
    ['No price is configured for the provider and model of these calls (sdlc.llm.pricing), so the cost is unknown', cache].filter(Boolean).join('\n'));
}

/** US dollars with cents, or more decimals for amounts under one cent. */
export function fmtUsd(value) {
  if (!isNum(value)) return DASH;
  if (value > 0 && value < 0.01) return `$${value.toFixed(4)}`;
  return `$${value.toFixed(2)}`;
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
      llmCostStat(counters),
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

/** The artifact version an exit approval is about: it is accepted only once approved. */
function reviewLine(action, on) {
  if (!isNum(action.reviewVersion)) return null;
  const hash = action.reviewHash ? String(action.reviewHash) : '';
  return h('p', { class: 'action-review' },
    h('span', {}, 'Under review: '),
    chip(`${action.stageId} v${action.reviewVersion}`, 'mono chip-review', 'Not accepted until this gate is approved'),
    hash ? h('span', { class: 'mono muted small', title: hash }, `#${hash.slice(0, 12)}`) : null,
    h('button', { type: 'button', class: 'btn small', 'data-fk': `review:${action.stageId}`, onclick: () => on.review(action.stageId, action.reviewVersion) }, `Open v${action.reviewVersion}`));
}

function approvalAction(action, draft, stageName, on) {
  const stage = stageName(action.stageId);
  const key = (field) => `${field}:${action.stageId}`;
  const disposition = draft(key('disposition'), 'REWORK');
  const version = isNum(action.reviewVersion) ? ` v${action.reviewVersion}` : '';
  // The decision is bound to the approval rendered here: the server refuses it (409) once another approval is pending.
  return h('article', { class: 'action approval', 'data-approval-id': action.approvalId ?? null },
    h('header', { class: 'action-head' },
      h('h3', {}, `Approval required: ${stage}`),
      chip(action.gateKind && `${humanize(action.gateKind)} gate`),
      action.requestedAt ? h('time', { class: 'muted small', datetime: action.requestedAt, title: fmtDateTime(action.requestedAt) }, `requested ${fmtTime(action.requestedAt)}`) : null),
    action.reason ? h('p', { class: 'action-reason' }, action.reason) : null,
    reviewLine(action, on),
    h('div', { class: 'action-row' },
      textInput(key('comment'), draft, { placeholder: 'Comment (optional)', 'aria-label': `Approval comment for ${stage}` }),
      h('button', { type: 'button', class: 'btn ok', onclick: () => on.approve(action.stageId, action.approvalId) }, `Approve${version}`)),
    h('div', { class: 'action-row' },
      textInput(key('reason'), draft, { placeholder: 'Rejection reason (required)', 'aria-label': `Rejection reason for ${stage}` }),
      h('select', { 'aria-label': `Rejection disposition for ${stage}`, 'data-draft': key('disposition'), 'data-fk': key('disposition') },
        h('option', { value: 'REWORK', selected: disposition === 'REWORK' }, 'Rework'),
        h('option', { value: 'ABORT', selected: disposition === 'ABORT' }, 'Abort run')),
      h('button', { type: 'button', class: 'btn danger', onclick: () => on.reject(action.stageId, action.approvalId) }, `Reject${version}`)));
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

const versionState = (a, reviewing) => {
  if (a.accepted) return 'accepted';
  return reviewing?.get(a.stageId) === a.version ? 'under review' : 'superseded';
};

export function renderArtifactList({ stages, artifacts, selectedStage, selectedKey, reviewing, onSelect }) {
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
          const state = versionState(a, reviewing);
          return h('button', {
            type: 'button', class: `vchip${a.accepted ? ' accepted' : ''}${state === 'under review' ? ' review' : ''}${selected ? ' selected' : ''}`,
            'aria-pressed': selected ? 'true' : 'false', 'data-fk': `ver:${a.stageId}:${a.version}`,
            title: `${humanize(a.kind)} v${a.version} \u2014 ${state}${a.hash ? `\n${a.hash}` : ''}`,
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
  const llm = Boolean(p.model) || (p.inputTokens ?? 0) > 0;
  const cache = llm && (isNum(p.cacheReadTokens) || isNum(p.cacheCreationTokens))
    ? `${fmtNum(p.cacheReadTokens ?? 0)} / ${fmtNum(p.cacheCreationTokens ?? 0)}` : null;
  const cost = !llm ? null : isNum(p.costMicroUsd) ? fmtUsd(p.costMicroUsd / 1_000_000) : 'unpriced';
  return [
    facts([
      ['Agent role', p.agentRole], ['Provider', p.providerId], ['Model', p.model], ['Attempt', p.attempt != null ? String(p.attempt) : null],
      ['Latency', isNum(p.latencyMs) ? fmtMs(p.latencyMs) : null], ['Tokens in / out', tokens],
      ...(cache ? [['Cache read / written', cache]] : []), ...(cost ? [['Estimated cost', cost]] : []),
      ['Prompt hash', p.promptHash],
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
  const dependencies = Array.isArray(view.dependencies) ? view.dependencies : null;
  return [
    markdown,
    dependencies ? section('dependencies', `Dependency inventory (${dependencies.length})`, renderDependencies(dependencies), true) : null,
    section('payload', 'Payload JSON', h('pre', { class: 'json' }, pretty(view.payload)), !view.markdown),
    section('provenance', 'Provenance', renderProvenance(view.provenance ?? summary.provenance)),
  ];
}

const DEPENDENCY_RENDER_CAP = 300;
const SEVERITY_ORDER = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

/** The dependency inventory of a security review: flagged declarations first, each flag as a severity chip. */
function renderDependencies(rows) {
  if (!rows.length) return placeholder('No dependency inventory: the run has no workspace (REPO runs inventory the Maven build files).');
  const worst = (d) => Math.min(...asList(d.flags).map((f) => SEVERITY_ORDER[f.severity] ?? 9), 9);
  const sorted = rows.slice().sort((a, b) => worst(a) - worst(b));
  const flagged = rows.filter((d) => asList(d.flags).length).length;
  return [
    h('p', { class: 'muted small' }, `${fmtNum(rows.length)} declarations, ${fmtNum(flagged)} flagged. An inventory and hygiene check of the Maven build files, not a vulnerability (CVE) scan.`),
    h('div', { class: 'table-wrap' }, h('table', { class: 'data deps' },
      h('thead', {}, h('tr', {}, ['Module', 'Kind', 'Coordinate', 'Scope', 'Section', 'Flags'].map((c) => h('th', { scope: 'col' }, c)))),
      h('tbody', {}, sorted.slice(0, DEPENDENCY_RENDER_CAP).map((d) => h('tr', { class: asList(d.flags).length ? 'flagged' : null },
        h('td', { class: 'mono small' }, d.module ?? DASH),
        h('td', {}, humanize(d.kind)),
        h('td', { class: 'mono small' }, d.coordinate ?? DASH),
        h('td', {}, d.scope ?? DASH),
        h('td', { class: 'small' }, d.section ?? DASH),
        h('td', {}, asList(d.flags).length
          ? asList(d.flags).map((f) => chip(humanize(f.kind), `sev-${statusToken(f.severity)}`, `${humanize(f.severity)} severity`))
          : h('span', { class: 'muted small' }, 'none'))))))),
    rows.length > DEPENDENCY_RENDER_CAP ? h('p', { class: 'muted small' }, `Showing ${DEPENDENCY_RENDER_CAP} of ${rows.length} declarations (flagged first); the payload JSON has all of them.`) : null,
  ];
}

export function renderArtifactDetail({ stage, summary, state, underReview = false, open, onToggle }) {
  if (!stage && !summary) return placeholder('Select a stage or a version chip, or click a node in the pipeline graph.');
  const s = stage ?? {};
  const art = state?.view ?? summary;
  // Acceptance changes when a gate is decided: the polled summary is authoritative, a cached view may predate it.
  const accepted = typeof summary?.accepted === 'boolean' ? summary.accepted : art?.accepted;
  const acceptance = (accepted) => {
    if (accepted) return chip('accepted', 'chip-ok');
    return underReview ? chip('under review', 'chip-review', 'Accepted only once the pending approval is granted') : chip('superseded', 'chip-muted');
  };
  return [
    h('header', { class: 'art-head' },
      h('h3', {}, s.name || s.id || summary?.stageId),
      art ? chip(`v${art.version ?? '?'}`, 'mono') : null,
      art?.kind ? chip(humanize(art.kind)) : null,
      typeof accepted === 'boolean' ? acceptance(accepted) : null,
      art?.hash ? h('span', { class: 'mono muted small', title: art.hash }, `#${String(art.hash).slice(0, 12)}`) : null,
      art?.createdAt ? h('span', { class: 'muted small', title: fmtDateTime(art.createdAt) }, fmtWhen(art.createdAt)) : null,
      signatureChip(state?.view)),
    stage ? facts([['Status', statusPill(s.status)], ['Attempts', fmtNum(s.attempts)], ['Agent', s.activeAgent || DASH], ['Duration', fmtMs(s.durationMs)]]) : null,
    s.lastError ? h('p', { class: 'error-box' }, h('strong', {}, 'Last error: '), s.lastError) : null,
    asList(s.feedback).length
      ? h('div', { class: 'feedback' }, h('h4', {}, 'Feedback for this stage'), h('ul', {}, asList(s.feedback).map((f) => h('li', {}, String(f)))))
      : null,
    artifactBody(summary, state, open, onToggle),
  ];
}

/** Signature state of a release change record: signed (keyId), signature invalid, or unsigned. */
function signatureChip(view) {
  if (!view || view.kind !== 'RELEASE_READINESS') return null;
  const sig = view.signature;
  if (!sig || typeof sig !== 'object') {
    return chip('unsigned', 'chip-muted', 'The change record carries no signature: it is not finalized yet, or no signing key was configured when the run completed.');
  }
  const signed = `${sig.algorithm ?? 'signature'} by key ${sig.keyId ?? '?'}, signed ${fmtDateTime(sig.signedAt)}`;
  if (view.signatureVerified === true) return chip(`signed (${sig.keyId ?? '?'})`, 'chip-ok', `${signed}; verified with the configured key.`);
  return chip('signature invalid', 'chip-bad', `${signed}; it does not verify with the configured key: the record changed after signing, or the key differs.`);
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

/** One line on the verification of the run's audit hash chain (GET /audit/integrity). */
function integrityLine(integrity) {
  if (!integrity || typeof integrity !== 'object') return h('p', { class: 'audit-integrity muted' }, 'Audit chain: checking\u2026');
  if (integrity.valid === true) {
    const n = isNum(integrity.length) ? integrity.length : 0;
    return h('p', { class: 'audit-integrity ok', title: integrity.summary ?? null },
      h('strong', {}, 'Audit chain: verified'), `, ${fmtNum(n)} event${n === 1 ? '' : 's'}`);
  }
  const hashes = [integrity.expectedHash && `expected ${integrity.expectedHash}`, integrity.actualHash && `found ${integrity.actualHash}`].filter(Boolean).join('\n');
  return h('p', { class: 'audit-integrity bad', title: hashes || null },
    h('strong', {}, `Audit chain: BROKEN at seq ${integrity.brokenAtSeq ?? '?'}`),
    integrity.summary ? h('span', { class: 'muted' }, ` \u2014 ${integrity.summary}`) : null);
}

export function renderAudit({ events, filter, follow, open, integrity }, { onToggleType, onClearFilter, onFollow, onToggleData }) {
  const counts = new Map();
  for (const e of events) counts.set(e.type, (counts.get(e.type) ?? 0) + 1);
  const shown = events.filter((e) => !filter.size || filter.has(e.type));
  const newestFirst = shown.slice(-AUDIT_RENDER_CAP).reverse();
  return [
    integrityLine(integrity),
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
      tile('LLM tokens', fmtCompact(tokens), `${fmtNum(llm.calls)} calls, ${fmtCompact(llm.inputTokens)} in / ${fmtCompact(llm.outputTokens)} out`),
      tile('LLM prompt cache', `${fmtCompact(llm.cacheReadTokens)} read`, `${fmtCompact(llm.cacheCreationTokens)} written \u00b7 input tokens served from / written to the provider's cache`),
      tile('Estimated LLM cost', costTile(llm), costNote(llm))),
  ];
}

function costTile(llm) {
  if (isNum(llm.estimatedCostUsd)) return fmtUsd(llm.estimatedCostUsd);
  return isNum(llm.calls) && llm.calls > 0 ? 'unpriced' : fmtUsd(0);
}

function costNote(llm) {
  const unpriced = isNum(llm.unpricedCalls) ? llm.unpricedCalls : 0;
  if (!unpriced) return 'From the configured prices (sdlc.llm.pricing)';
  const lowerBound = isNum(llm.estimatedCostUsd) ? 'a lower bound: ' : '';
  return `${lowerBound}${fmtNum(unpriced)} call${unpriced === 1 ? '' : 's'} without a configured price (sdlc.llm.pricing)`;
}

// ---------------------------------------------------------------- policy

/** Header entry of the policy in force: the active pack (name, version, hash) or the built-in rules. */
export function renderPolicyChip(policy, onOpen) {
  if (!policy || typeof policy !== 'object') return null;
  const pack = policy.pack;
  const text = pack ? `Policy: ${pack.name} v${pack.version}` : 'Policy: built-in rules';
  const title = pack
    ? [`Policy pack ${pack.name} ${pack.version}`, `SHA-256 ${pack.hash}`, `${asList(pack.rules).length} declarative rules`,
      asList(pack.disabledRules).length ? `Disables ${asList(pack.disabledRules).join(', ')}` : 'Disables no built-in rule'].join('\n')
    : `${asList(policy.builtInRules).length} built-in rules; no policy pack (sdlc.policy.pack) is configured`;
  return h('button', { type: 'button', class: `policy-chip${pack ? ' pack' : ''}`, title, onclick: onOpen },
    text, pack?.hash ? h('span', { class: 'mono muted' }, ` #${String(pack.hash).slice(0, 8)}`) : null);
}

/** The policy in force, above the findings of the selected run. */
export function renderPolicyInForce(policy) {
  if (!policy || typeof policy !== 'object') return null;
  const pack = policy.pack;
  const disabled = new Set(asList(pack?.disabledRules));
  return h('details', { class: 'raw policy-in-force' },
    h('summary', {}, pack ? `Policy in force: pack ${pack.name} ${pack.version} (#${String(pack.hash ?? '').slice(0, 12)}) and the built-in rules` : 'Policy in force: built-in rules'),
    h('p', { class: 'muted small' }, 'Built-in rules: ',
      asList(policy.builtInRules).map((r) => chip(r.id, disabled.has(r.id) || r.active === false ? 'mono chip-muted' : 'mono',
        `${r.name}${r.active === false ? ' (disabled by the pack)' : ''}${r.disableable === false ? '; cannot be disabled' : ''}`))),
    pack && asList(pack.rules).length
      ? h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
        h('thead', {}, h('tr', {}, ['Rule', 'Phase', 'Stages', 'Effect', 'Severity', 'Message'].map((c) => h('th', { scope: 'col' }, c)))),
        h('tbody', {}, asList(pack.rules).map((r) => h('tr', { title: r.description || null },
          h('td', { class: 'mono nowrap' }, r.id), h('td', {}, humanize(r.phase)),
          h('td', { class: 'mono small' }, asList(r.stages).join(', ') || 'all'),
          h('td', {}, humanize(r.effect)), h('td', {}, chip(humanize(r.severity), `sev-${statusToken(r.severity)}`)),
          h('td', {}, r.message ?? DASH))))))
      : null);
}

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

// ---------------------------------------------------------------- evaluation

/** A 0..1 score as a percentage with a bar (colour from the score, the number is always shown). */
function scoreBar(value, label) {
  const pct = isNum(value) ? Math.max(0, Math.min(1, value)) * 100 : 0;
  const level = !isNum(value) ? '' : value < 0.5 ? ' danger' : value < 0.75 ? ' warn' : '';
  return h('span', { class: 'score' },
    h('span', { class: `meter-track${level}`, role: 'img', 'aria-label': `${label ?? 'Score'} ${fmtPct(value)}` },
      h('span', { class: 'meter-fill', style: { width: `${pct}%` } })),
    h('span', { class: 'score-value' }, fmtPct(value)));
}

const weakest = (stage) => asList(stage.criteria).filter((c) => c.applies)
  .reduce((min, c) => (!min || c.value < min.value ? c : min), null);

/** The evaluation of the selected run: overall score, a score bar per stage and every criterion with its rationale. */
export function renderEvaluation({ status, report, error, onRefresh }) {
  if (!report) {
    if (status === 'error') return placeholder(`Could not evaluate this run (${error?.detail || error?.title || 'error'}).`);
    return placeholder('Evaluating the artifacts of this run\u2026');
  }
  const stages = asList(report.stages);
  return [
    h('div', { class: 'card-head' },
      h('p', { class: 'muted small' }, 'Deterministic scores of the accepted artifact of every stage (else the latest one under review); recomputed whenever the artifacts change.'),
      h('button', { type: 'button', class: 'btn small', onclick: onRefresh, disabled: status === 'loading' || status === 'refreshing' }, status === 'refreshing' ? 'Refreshing\u2026' : 'Re-evaluate')),
    h('div', { class: 'eval-overall' }, h('strong', {}, 'Overall'), scoreBar(report.overall, 'Overall score')),
    stages.length
      ? h('div', { class: 'table-wrap' }, h('table', { class: 'data eval' },
        h('thead', {}, h('tr', {}, ['Stage', 'Score', 'Weakest criterion'].map((c) => h('th', { scope: 'col' }, c)))),
        h('tbody', {}, stages.map((s) => {
          const low = weakest(s);
          return h('tr', {},
            h('td', { class: 'mono' }, s.stageId),
            h('td', { class: 'score-cell' }, scoreBar(s.score, `${s.stageId} score`)),
            h('td', {}, low ? `${low.name} (${fmtPct(low.value)})` : DASH));
        }))))
      : placeholder('No stage has produced an artifact yet.'),
    stages.map((s) => h('details', { class: 'raw' },
      h('summary', {}, `${s.stageId} \u00b7 ${fmtPct(s.score)}`),
      h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
        h('thead', {}, h('tr', {}, ['Criterion', 'Weight', 'Value', 'Rationale'].map((c) => h('th', { scope: 'col' }, c)))),
        h('tbody', {}, asList(s.criteria).map((c) => h('tr', { class: c.applies ? null : 'muted' },
          h('td', { class: 'mono small' }, c.name),
          h('td', {}, c.applies ? fmtRatio(c.weight) : 'n/a'),
          h('td', { class: 'score-cell' }, scoreBar(c.value, c.name)),
          h('td', { class: 'small' }, c.rationale ?? DASH)))))))),
    asList(report.notes).length ? h('ul', { class: 'muted small' }, asList(report.notes).map((n) => h('li', {}, String(n)))) : null,
  ];
}
