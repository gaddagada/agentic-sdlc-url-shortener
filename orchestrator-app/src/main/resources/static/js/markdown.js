// Tiny, safe Markdown renderer for artifact documents.
// Safety model: the whole source is HTML-escaped FIRST; afterwards only the fixed
// tags emitted below are introduced, and no attribute ever carries source text.
// Supported: ATX headings, paragraphs, nested bullet/numbered lists (with [ ]/[x]),
// pipe tables, fenced code blocks, blockquotes, rules, `code`, **bold**, *italic*, ~~del~~.
import { escapeHtml } from './util.js';

const LIST_ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
const FENCE = /^\s*(`{3,}|~{3,})/;
const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
const QUOTE = /^\s*&gt;\s?/;

export function renderMarkdown(source) {
  const escaped = escapeHtml(String(source ?? '').replace(/\u0000/g, ''));
  return renderBlocks(escaped.replace(/\r\n?/g, '\n').split('\n'));
}

function renderBlocks(lines) {
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
    } else if (FENCE.test(line)) {
      const marker = line.match(FENCE)[1];
      const body = [];
      for (i++; i < lines.length && !lines[i].trim().startsWith(marker); i++) body.push(lines[i]);
      i++; // closing fence (or end of input)
      out.push(`<pre class="md-code"><code>${body.join('\n')}</code></pre>`);
    } else if (HEADING.test(line)) {
      const [, hashes, text] = line.match(HEADING);
      const level = Math.min(hashes.length + 2, 6); // the artifact title sits below the panel's own h2
      out.push(`<h${level}>${inline(text)}</h${level}>`);
      i++;
    } else if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const header = splitRow(line);
      const aligns = splitRow(lines[i + 1]).map(alignClass);
      const rows = [];
      for (i += 2; i < lines.length && lines[i].includes('|') && lines[i].trim(); i++) rows.push(splitRow(lines[i]));
      out.push(renderTable(header, aligns, rows));
    } else if (RULE.test(line)) {
      out.push('<hr>');
      i++;
    } else if (LIST_ITEM.test(line)) {
      const [html, next] = parseList(lines, i);
      out.push(html);
      i = next;
    } else if (QUOTE.test(line)) {
      const quoted = [];
      for (; i < lines.length && QUOTE.test(lines[i]); i++) quoted.push(lines[i].replace(QUOTE, ''));
      out.push(`<blockquote>${renderBlocks(quoted)}</blockquote>`);
    } else {
      const para = [];
      for (; i < lines.length && lines[i].trim() && !startsBlock(lines, i); i++) para.push(inline(lines[i].trim()));
      out.push(`<p>${para.join('<br>')}</p>`); // generated documents use one fact per line
    }
  }
  return out.join('\n');
}

function startsBlock(lines, i) {
  const line = lines[i];
  return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || LIST_ITEM.test(line) || QUOTE.test(line)
    || (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1]));
}

// ---------------------------------------------------------------- tables

function splitRow(line) {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  return s.split(/(?<!\\)\|/).map((cell) => cell.trim().replace(/\\\|/g, '|'));
}

const isTableSeparator = (line) => line.includes('|') && line.includes('-') && splitRow(line).every((c) => /^:?-+:?$/.test(c));

function alignClass(cell) {
  if (cell.startsWith(':') && cell.endsWith(':')) return ' class="al-c"';
  return cell.endsWith(':') ? ' class="al-r"' : '';
}

function renderTable(header, aligns, rows) {
  const cells = (row, tag) => header.map((_, c) => `<${tag}${aligns[c] ?? ''}>${inline(row[c] ?? '')}</${tag}>`).join('');
  const body = rows.map((row) => `<tr>${cells(row, 'td')}</tr>`).join('');
  return `<div class="md-table"><table><thead><tr>${cells(header, 'th')}</tr></thead><tbody>${body}</tbody></table></div>`;
}

// ---------------------------------------------------------------- lists

function parseList(lines, start) {
  const items = [];
  let i = start;
  while (i < lines.length) {
    const m = lines[i].match(LIST_ITEM);
    if (m) {
      items.push({ indent: m[1].replace(/\t/g, '    ').length, ordered: /\d/.test(m[2]), text: m[3] });
    } else if (lines[i].trim() && /^\s+/.test(lines[i]) && !startsBlock(lines, i)) {
      items.at(-1).text += ` ${lines[i].trim()}`; // lazy continuation line
    } else {
      // Anything else ends the list; a blank line followed by another item keeps it open.
      const blankBeforeItem = !lines[i].trim() && i + 1 < lines.length && LIST_ITEM.test(lines[i + 1]);
      if (!blankBeforeItem) break;
    }
    i++;
  }
  return [buildList(items), i];
}

/** Builds nested lists from indentation: a deeper item opens a list inside the previous <li>. */
function buildList(items) {
  let html = '';
  const stack = [];
  for (const item of items) {
    const tag = item.ordered ? 'ol' : 'ul';
    if (!stack.length || item.indent > stack.at(-1).indent) {
      html += `<${tag}>`;
      stack.push({ indent: item.indent, tag });
    } else {
      while (stack.length > 1 && item.indent < stack.at(-1).indent) html += `</li></${stack.pop().tag}>`;
      if (stack.at(-1).tag === tag) {
        html += '</li>';
      } else {
        // Same level, different list type (bullets followed by numbers): close it and open a new list.
        const { indent, tag: closing } = stack.pop();
        html += `</li></${closing}><${tag}>`;
        stack.push({ indent, tag });
      }
    }
    const text = item.text.replace(/^\[( |x|X)\]\s+/, (_, mark) => (mark === ' ' ? '\u2610 ' : '\u2611 '));
    html += `<li>${inline(text)}`;
  }
  while (stack.length) html += `</li></${stack.pop().tag}>`;
  return html;
}

// ---------------------------------------------------------------- inline

function inline(text) {
  const spans = [];
  const s = text
    .replace(/`([^`]+)`/g, (_, code) => `\u0000${spans.push(code) - 1}\u0000`) // protect code spans
    .replace(/\*\*(?=\S)(.+?)(?<=\S)\*\*/g, '<strong>$1</strong>')
    .replace(/__(?=\S)(.+?)(?<=\S)__/g, '<strong>$1</strong>')
    .replace(/(^|[^*\w])\*(?=\S)(.+?)(?<=\S)\*(?![*\w])/g, '$1<em>$2</em>')
    .replace(/(^|[^_\w])_(?=\S)(.+?)(?<=\S)_(?![_\w])/g, '$1<em>$2</em>')
    .replace(/~~(?=\S)(.+?)(?<=\S)~~/g, '<del>$1</del>');
  return s.replace(/\u0000(\d+)\u0000/g, (_, n) => `<code>${spans[Number(n)]}</code>`);
}
