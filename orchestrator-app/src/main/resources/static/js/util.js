// Shared DOM, formatting and storage helpers.
// Every DOM node is built with createElement + text nodes, so strings coming from
// the server are never parsed as HTML. The only innerHTML sink in the console is
// the Markdown renderer, which escapes its input first (see markdown.js).

const SVG_NS = 'http://www.w3.org/2000/svg';
export const DASH = '\u2014';

function append(parent, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false || child === '') continue;
    parent.append(child instanceof Node ? child : String(child)); // strings become text nodes
  }
  return parent;
}

function applyAttrs(el, attrs, isSvg) {
  for (const [key, value] of Object.entries(attrs ?? {})) {
    if (value == null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (!isSvg && key === 'class') el.className = value;
    else if (!isSvg && (key === 'value' || key === 'checked')) el[key] = value;
    else el.setAttribute(key, value === true ? '' : String(value));
  }
}

/** Creates an HTML element: h('button', { class: 'btn', onclick }, 'Label'). */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  applyAttrs(el, attrs, false);
  return append(el, children);
}

/** Creates an SVG element with the same calling convention as h(). */
export function svg(tag, attrs, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  applyAttrs(el, attrs, true);
  return append(el, children);
}

/** Replaces the children of `el`. */
export function mount(el, ...children) {
  el.replaceChildren();
  return append(el, children);
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ESCAPES[c]);

// ---------------------------------------------------------------- formatting

export const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
export const asList = (v) => (Array.isArray(v) ? v : []);
export const pretty = (value) => {
  try {
    return JSON.stringify(value ?? null, null, 2);
  } catch {
    return String(value);
  }
};
export const shortId = (id) => (id ? String(id).slice(0, 8) : DASH);
export const clip = (text, max) => {
  const s = String(text ?? '');
  return s.length > max ? `${s.slice(0, max - 1)}\u2026` : s;
};

/** WAITING_HUMAN -> "Waiting human". */
export const humanize = (value) => {
  if (value == null || value === '') return DASH;
  const s = String(value).replaceAll('_', ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
};

/** Milliseconds as "850 ms", "1.2 s", "3 m 4 s" or "1 h 5 m". */
export function fmtMs(ms) {
  if (!isNum(ms)) return DASH;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const total = Math.round(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  return hours ? `${hours} h ${minutes} m` : `${minutes} m ${total % 60} s`;
}

export const fmtNum = (v) => (isNum(v) ? v.toLocaleString() : DASH);
const compact = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
export const fmtCompact = (v) => (isNum(v) ? compact.format(v) : DASH);
export const fmtPct = (ratio) => (isNum(ratio) ? `${(ratio * 100).toFixed(1).replace(/\.0$/, '')}%` : DASH);
export const fmtRatio = (v) => (isNum(v) ? v.toFixed(2).replace(/\.?0+$/, '') : DASH);

function toDate(iso) {
  const d = iso ? new Date(iso) : null;
  return d && !Number.isNaN(d.getTime()) ? d : null;
}

export const fmtDateTime = (iso) => toDate(iso)?.toLocaleString() ?? DASH;

export function fmtTime(iso) {
  const d = toDate(iso);
  return d ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : DASH;
}

/** Short timestamp: time of day for today, otherwise date and time. */
export function fmtWhen(iso) {
  const d = toDate(iso);
  if (!d) return DASH;
  const sameDay = d.toDateString() === new Date().toDateString();
  return sameDay
    ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

// ---------------------------------------------------------------- small UI atoms

/** CSS-safe status token so a status string can only ever select a known class. */
export const statusToken = (status) => String(status ?? 'UNKNOWN').replace(/[^A-Za-z_]/g, '');

export const statusPill = (status, extra = '') =>
  h('span', { class: `pill st-${statusToken(status)} ${extra}`.trim() }, humanize(status));

export const chip = (text, extra = '', title = null) =>
  text == null || text === '' ? null : h('span', { class: `chip ${extra}`.trim(), title }, String(text));

// ---------------------------------------------------------------- storage

/** localStorage can throw (privacy mode, disabled storage, quota); never let that break the UI. */
export const store = {
  get(key, fallback = null) {
    try {
      return localStorage.getItem(key) ?? fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* storage unavailable: the value simply is not remembered */
    }
  },
};
