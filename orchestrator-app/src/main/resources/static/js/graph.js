// SVG renderers for the pipeline DAG (GraphView) and the artifact lineage graph (LineageView).
// Layout: one column per layer, nodes evenly spaced vertically inside their column,
// cubic Bezier edges from the right edge of the source to the left edge of the target.
// Joins (design, verify, release-readiness) show up as nodes fed by several edges.
import { h, svg, clip, humanize, statusToken, isNum } from './util.js';

const PIPELINE = { w: 150, h: 74, colGap: 42, pad: 14, slot: 92 }; // slot: vertical room per node in the tallest column
const LINEAGE = { w: 172, h: 44, colGap: 40, pad: 12, slot: 56 };
const LEGEND = ['PENDING', 'RUNNING', 'RETRY_SCHEDULED', 'AWAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'STALE', 'CANCELLED'];
const round = (n) => Math.round(n * 10) / 10;

/** Status legend shown next to the pipeline title. */
export const renderLegend = () =>
  LEGEND.map((s) => h('span', { class: `legend-item st-${s}` }, h('span', { class: 'swatch', 'aria-hidden': 'true' }), humanize(s)));

/** Longest-path layering: used when the server omits `layer`, and as the lineage fallback. */
export function longestPathLayers(ids, edges) {
  const preds = predecessors(edges);
  const memo = new Map();
  const onPath = new Set();
  const visit = (id) => {
    if (memo.has(id)) return memo.get(id);
    if (onPath.has(id)) return 0; // cycle guard: the server validates acyclicity, never loop regardless
    onPath.add(id);
    const ps = (preds.get(id) ?? []).filter((p) => ids.includes(p));
    const layer = ps.length ? Math.max(...ps.map((p) => visit(p) + 1)) : 0;
    memo.set(id, layer);
    return layer;
  };
  return new Map(ids.map((id) => [id, visit(id)]));
}

function predecessors(edges) {
  const preds = new Map();
  for (const e of edges) (preds.get(e.to) ?? preds.set(e.to, []).get(e.to)).push(e.from);
  return preds;
}

/** Groups keys into columns by layer (ascending); empty layers are dropped. */
function columnsFrom(keys, layerOf) {
  const byLayer = new Map();
  for (const key of keys) {
    const layer = Number(layerOf(key)) || 0;
    (byLayer.get(layer) ?? byLayer.set(layer, []).get(layer)).push(key);
  }
  return [...byLayer.keys()].sort((a, b) => a - b).map((layer) => byLayer.get(layer));
}

/** Orders a column by the mean y of already placed predecessors, which removes most edge crossings. */
function byBarycentre(column, preds, pos) {
  const centre = (key) => {
    const ys = (preds.get(key) ?? []).map((p) => pos.get(p)?.y).filter(isNum);
    return ys.length ? ys.reduce((a, b) => a + b, 0) / ys.length : Infinity;
  };
  return column
    .map((key, index) => ({ key, index, c: centre(key) }))
    .sort((a, b) => a.c - b.c || a.index - b.index)
    .map((entry) => entry.key);
}

function layout(columns, dims, { preds, compare }) {
  const rows = Math.max(1, ...columns.map((c) => c.length));
  const inner = rows * dims.slot;
  const width = dims.pad * 2 + columns.length * dims.w + Math.max(0, columns.length - 1) * dims.colGap;
  const pos = new Map();
  columns.forEach((column, ci) => {
    const ordered = compare ? [...column].sort(compare) : byBarycentre(column, preds, pos);
    const step = inner / ordered.length;
    ordered.forEach((key, ri) => {
      pos.set(key, { x: dims.pad + ci * (dims.w + dims.colGap), y: round(dims.pad + step * (ri + 0.5) - dims.h / 2) });
    });
  });
  return { pos, width, height: inner + dims.pad * 2 };
}

function edgePath(a, b, dims) {
  const [x1, y1, x2, y2] = [a.x + dims.w, a.y + dims.h / 2, b.x - 1, b.y + dims.h / 2];
  const dx = Math.max(20, Math.abs(x2 - x1) / 2);
  return `M${x1},${y1} C${round(x1 + dx)},${y1} ${round(x2 - dx)},${y2} ${x2},${y2}`;
}

function defs(prefix) {
  const marker = (kind) =>
    svg('marker', { id: `${prefix}-arrow${kind ? `-${kind}` : ''}`, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 8, markerHeight: 8, markerUnits: 'userSpaceOnUse', orient: 'auto' },
      svg('path', { class: `arrow ${kind}`.trim(), d: 'M0,0 L10,5 L0,10 z' }));
  return svg('defs', {},
    svg('pattern', { id: `${prefix}-hatch`, patternUnits: 'userSpaceOnUse', width: 8, height: 8, patternTransform: 'rotate(45)' },
      svg('rect', { class: 'hatch-bg', width: 8, height: 8 }),
      svg('line', { class: 'hatch-line', x1: 0, y1: 0, x2: 0, y2: 8 })),
    marker(''), marker('done'), marker('active'));
}

function wrapWords(text, max, maxLines) {
  const lines = [];
  for (const word of String(text).split(/\s+/).filter(Boolean)) {
    const last = lines.at(-1);
    if (last !== undefined && `${last} ${word}`.length <= max) lines[lines.length - 1] = `${last} ${word}`;
    else lines.push(word);
  }
  if (lines.length > maxLines) lines.splice(maxLines - 1, Infinity, lines.slice(maxLines - 1).join(' '));
  return lines.map((line) => clip(line, max));
}

const keyActivate = (fn) => (e) => {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    fn();
  }
};

// ---------------------------------------------------------------- pipeline

const lockGlyph = (approval, x, y) =>
  svg('g', { class: `lock ap-${statusToken(approval.status)}`, transform: `translate(${x},${y})` },
    svg('path', { class: 'shackle', d: 'M3.5 7V4.5a3 3 0 0 1 6 0V7' }),
    svg('rect', { x: 1.5, y: 7, width: 10, height: 8, rx: 1.5 }));

const versionChip = (version, x, y) =>
  svg('g', { class: 'vtag', transform: `translate(${x},${y})` },
    svg('rect', { width: 24, height: 15, rx: 7.5 }),
    svg('text', { x: 12, y: 11, 'text-anchor': 'middle' }, `v${version}`));

function pipelineNode(node, p, isSelected, onSelect) {
  const { w, h: height } = PIPELINE;
  const name = node.name || node.id;
  const approval = node.approval && typeof node.approval === 'object' ? node.approval : null;
  const attempts = isNum(node.attempts) && node.attempts > 1 ? ` \u00b7 attempt ${node.attempts}` : '';
  const label = [
    `${name}: ${humanize(node.status)}${attempts}`,
    approval && `approval ${humanize(approval.status)} (${approval.kind ?? 'gate'})`,
    isNum(node.artifactVersion) && `artifact v${node.artifactVersion}`,
  ].filter(Boolean).join(', ');
  const activate = () => onSelect?.(node.id);
  return svg('g', {
    class: `node st-${statusToken(node.status)}${isSelected ? ' selected' : ''}`,
    transform: `translate(${p.x},${p.y})`,
    tabindex: 0, role: 'button', 'aria-label': label, 'aria-pressed': isSelected ? 'true' : 'false', 'data-fk': `node:${node.id}`,
    onclick: activate, onkeydown: keyActivate(activate),
  },
  svg('title', {}, [label, node.role && `Role: ${node.role}`, node.activeAgent && `Agent: ${node.activeAgent}`, approval?.reason].filter(Boolean).join('\n')),
  isSelected ? svg('rect', { class: 'halo', x: -4, y: -4, width: w + 8, height: height + 8, rx: 12 }) : null,
  svg('rect', { class: 'box', width: w, height, rx: 9 }),
  wrapWords(name, 17, 2).map((line, i) => svg('text', { class: 'name', x: 10, y: 18 + i * 14 }, line)),
  svg('text', { class: 'status', x: 10, y: 50 }, `${humanize(node.status)}${attempts}`),
  svg('text', { class: 'agent', x: 10, y: 65 }, clip(node.activeAgent ?? '', 18)),
  isNum(node.artifactVersion) ? versionChip(node.artifactVersion, w - 30, height - 21) : null,
  approval ? lockGlyph(approval, w - 20, 6) : null);
}

/** Renders GraphView { nodes, edges } or returns null when there is nothing to draw. */
export function renderPipeline(graph, { selected, onSelect } = {}) {
  const nodes = (Array.isArray(graph?.nodes) ? graph.nodes : []).filter((n) => n?.id);
  if (!nodes.length) return null;
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const edges = (Array.isArray(graph.edges) ? graph.edges : []).filter((e) => byId.has(e?.from) && byId.has(e?.to));
  const ids = [...byId.keys()];
  const fallback = nodes.every((n) => Number.isInteger(n.layer)) ? null : longestPathLayers(ids, edges);
  const columns = columnsFrom(ids, (id) => fallback?.get(id) ?? byId.get(id).layer);
  const { pos, width, height } = layout(columns, PIPELINE, { preds: predecessors(edges) });
  const statusOf = (id) => statusToken(byId.get(id).status);

  return svg('svg', { class: 'dag pipeline', viewBox: `0 0 ${width} ${height}`, role: 'group', 'aria-label': `Pipeline graph, ${nodes.length} stages`, style: { maxWidth: `${width}px` } },
    defs('pg'),
    svg('g', { class: 'edges', 'aria-hidden': 'true' }, edges.map((e) => {
      const done = statusOf(e.from) === 'SUCCEEDED';
      const kind = done && ['RUNNING', 'RETRY_SCHEDULED'].includes(statusOf(e.to)) ? 'active' : done ? 'done' : '';
      return svg('path', { class: `edge ${kind}`.trim(), d: edgePath(pos.get(e.from), pos.get(e.to), PIPELINE), 'marker-end': `url(#pg-arrow${kind ? `-${kind}` : ''})` });
    })),
    svg('g', { class: 'nodes' }, nodes.map((n) => pipelineNode(n, pos.get(n.id), n.id === selected, onSelect))));
}

// ---------------------------------------------------------------- lineage

function lineageNode(node, p, isSelected, onSelect) {
  const { w, h: height } = LINEAGE;
  const hash = node.hash ? String(node.hash).slice(0, 8) : '';
  const label = `${node.stageId} version ${node.version ?? '?'}, ${node.accepted ? 'accepted' : 'superseded'}${hash ? `, hash ${hash}` : ''}`;
  const activate = () => onSelect?.(node);
  return svg('g', {
    class: `lnode ${node.accepted ? 'accepted' : 'superseded'}${isSelected ? ' selected' : ''}`,
    transform: `translate(${p.x},${p.y})`,
    tabindex: 0, role: 'button', 'aria-label': label, 'data-fk': `art:${node.artifactId}`,
    onclick: activate, onkeydown: keyActivate(activate),
  },
  svg('title', {}, `${label}\n${node.kind ?? ''}\n${node.hash ?? ''}`),
  isSelected ? svg('rect', { class: 'halo', x: -4, y: -4, width: w + 8, height: height + 8, rx: 10 }) : null,
  svg('rect', { class: 'box', width: w, height, rx: 8 }),
  svg('text', { class: 'name', x: 10, y: 18 }, clip(`${node.stageId} v${node.version ?? '?'}`, 23)),
  svg('text', { class: 'agent', x: 10, y: 34 }, clip([node.kind && humanize(node.kind), hash].filter(Boolean).join(' \u00b7 '), 25)));
}

/**
 * Renders LineageView { nodes, edges }: columns follow the pipeline layer of each
 * artifact's stage (`layerOf`: stageId -> layer), versions stacked in stage order;
 * `selectedKey` ("stageId|version") highlights one version and its edges.
 */
export function renderLineage(lineage, { layerOf = new Map(), stageOrder = new Map(), selectedKey, onSelect } = {}) {
  const nodes = (Array.isArray(lineage?.nodes) ? lineage.nodes : []).filter((n) => n?.artifactId);
  if (!nodes.length) return null;
  // Matched on stageId|version so the highlight works even if summary ids and lineage ids differ.
  const selectedId = nodes.find((n) => `${n.stageId}|${n.version}` === selectedKey)?.artifactId;
  const byId = new Map(nodes.map((n) => [n.artifactId, n]));
  const edges = (Array.isArray(lineage.edges) ? lineage.edges : []).filter((e) => byId.has(e?.from) && byId.has(e?.to));
  const ids = [...byId.keys()];
  const fallback = longestPathLayers(ids, edges);
  const rank = (id) => stageOrder.get(byId.get(id).stageId) ?? 999;
  const compare = (a, b) => rank(a) - rank(b) || (byId.get(a).version ?? 0) - (byId.get(b).version ?? 0);
  const columns = columnsFrom(ids, (id) => layerOf.get(byId.get(id).stageId) ?? fallback.get(id));
  const { pos, width, height } = layout(columns, LINEAGE, { compare });

  return svg('svg', { class: 'dag lineage', viewBox: `0 0 ${width} ${height}`, role: 'group', 'aria-label': `Artifact lineage, ${nodes.length} versions`, style: { maxWidth: `${width}px` } },
    defs('ln'),
    svg('g', { class: 'edges', 'aria-hidden': 'true' }, edges.map((e) => {
      const highlighted = selectedId != null && (e.from === selectedId || e.to === selectedId);
      const faded = !(byId.get(e.from).accepted && byId.get(e.to).accepted);
      return svg('path', {
        class: `edge${highlighted ? ' active' : ''}${faded ? ' faded' : ''}`,
        d: edgePath(pos.get(e.from), pos.get(e.to), LINEAGE),
        'marker-end': `url(#ln-arrow${highlighted ? '-active' : ''})`,
      });
    })),
    svg('g', { class: 'nodes' }, nodes.map((n) => lineageNode(n, pos.get(n.artifactId), n.artifactId === selectedId, onSelect))));
}
