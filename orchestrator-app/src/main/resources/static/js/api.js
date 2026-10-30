// Tiny fetch wrapper: JSON in and out, a timeout on every request (AbortController),
// optional cancellation by the caller, and RFC 9457 Problem Details mapped to ApiError.
// Also the server-sent event client: fetch + ReadableStream (not EventSource, which cannot
// send the X-Api-Key or Authorization header), with Last-Event-ID reconnects.
import { store } from './util.js';

/** sessionStorage can throw like localStorage; the access token is kept per tab only. */
function sessionValue(key) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

export class ApiError extends Error {
  constructor({ status = 0, title = 'Request failed', detail = '', errors = [], network = false, aborted = false, type = '', holder = null }) {
    super(detail || title);
    // type: the RFC 9457 problem type URI; holder: the engine node executing the run (409 run-leased-elsewhere)
    Object.assign(this, { status, title, detail, errors, network, aborted, type, holder });
  }
}

/** Problem type of a command refused because another engine node is executing the run right now. */
export const RUN_LEASED_ELSEWHERE = 'https://agenticsdlc.dev/problems/run-leased-elsewhere';

let reachabilityListener = () => {};
/** Registers a callback told whether the backend answered (true) or could not be reached (false). */
export const onReachability = (fn) => {
  reachabilityListener = fn;
};

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** The credentials of the security mode: X-Api-Key (api-key mode) and a bearer token (oidc mode), when stored. */
function authHeaders() {
  const headers = {};
  const apiKey = store.get('sdlc.apiKey');
  if (apiKey) headers['X-Api-Key'] = apiKey;
  const accessToken = sessionValue('sdlc.accessToken'); // oidc mode: an access token obtained from the identity provider
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return headers;
}

function toApiError(res, body) {
  const type = res.headers.get('content-type') ?? '';
  const problem = body && typeof body === 'object' && (type.includes('problem+json') || 'title' in body || 'detail' in body);
  const statusLine = `HTTP ${res.status}${res.statusText ? ` ${res.statusText}` : ''}`;
  let detail = problem ? String(body.detail ?? body.message ?? '') : '';
  if (res.status === 401) {
    const bearer = (res.headers.get('www-authenticate') ?? '').toLowerCase().startsWith('bearer');
    const hint = bearer
      ? 'Store an access token with sessionStorage.setItem("sdlc.accessToken", "<token>") and reload.'
      : 'Store the API key with localStorage.setItem("sdlc.apiKey", "<key>") and reload.';
    detail = detail ? `${detail} ${hint}` : `The credentials are missing or wrong. ${hint}`;
  }
  const errors = problem && Array.isArray(body.errors)
    ? body.errors.map((e) => (typeof e === 'string' ? e : [e?.field, e?.message ?? e?.defaultMessage].filter(Boolean).join(': ')))
    : [];
  return new ApiError({
    status: res.status,
    title: problem && body.title ? `${body.title} (${res.status})` : statusLine,
    detail,
    errors,
    type: problem && typeof body.type === 'string' ? body.type : '',
    holder: problem && typeof body.holder === 'string' ? body.holder : null,
  });
}

/**
 * Calls the orchestrator API. Resolves with the parsed JSON body (null when empty).
 * Rejects with ApiError: `network` for unreachable/timeout, `aborted` when the caller cancelled.
 * `accept` overrides the Accept header (for example text/markdown); the body is then returned as text.
 */
export async function api(path, { method = 'GET', body, operatorId, signal, timeoutMs = 10_000, accept } = {}) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forwardAbort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', forwardAbort, { once: true });

  const headers = { Accept: accept ?? 'application/json, application/problem+json', ...authHeaders() };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && operatorId) headers['X-Operator-Id'] = operatorId;

  try {
    const res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: 'no-store',
    });
    const text = await res.text();
    reachabilityListener(true);
    if (!res.ok) throw toApiError(res, parseJson(text));
    return accept && !accept.includes('json') ? text : parseJson(text);
  } catch (err) {
    if (err instanceof ApiError) throw err;
    if (signal?.aborted && !timedOut) throw new ApiError({ title: 'Request cancelled', aborted: true });
    reachabilityListener(false);
    throw new ApiError({
      title: timedOut ? 'Request timed out' : 'Backend unreachable',
      detail: `${method} ${path}`,
      network: true,
    });
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', forwardAbort);
  }
}

// ---------------------------------------------------------------- server-sent events

/** Whether this browser can read a streamed fetch response (otherwise the console only polls). */
export const streamingSupported = typeof ReadableStream === 'function' && typeof TextDecoder === 'function'
  && typeof Response === 'function' && 'body' in Response.prototype;

/**
 * Returns a function that consumes text/event-stream text (in arbitrary chunks) and calls onEvent with
 * { type, data, id } for every complete event (id is null for events without one). Implements the parts of the
 * WHATWG event stream format the server uses: event, data (multi-line), id, comments; CRLF, LF and CR line ends.
 */
export function sseParser(onEvent) {
  let buffer = '';
  let current = { type: '', data: [], id: null };
  const dispatch = () => {
    if (current.data.length) onEvent({ type: current.type || 'message', data: current.data.join('\n'), id: current.id });
    current = { type: '', data: [], id: null };
  };
  return (chunk) => {
    buffer += chunk;
    for (;;) {
      const index = buffer.search(/\r\n|\r|\n/);
      if (index < 0) return;
      // a CR at the very end may be the first half of CRLF: wait for the next chunk
      if (buffer[index] === '\r' && index === buffer.length - 1) return;
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + (buffer.startsWith('\r\n', index) ? 2 : 1));
      if (line === '') {
        dispatch();
        continue;
      }
      if (line.startsWith(':')) continue; // comment
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') current.type = value;
      else if (field === 'data') current.data.push(value);
      else if (field === 'id' && !value.includes('\0')) current.id = value;
    }
  };
}

const RETRY_MS = [1_000, 2_000, 5_000, 10_000, 30_000];
const SILENCE_MS = 45_000; // three missed heartbeats: the connection is considered dead

/**
 * One server-sent event stream read with fetch (so the auth headers can be sent). It reconnects after network
 * errors, server errors and a silent connection (no heartbeat), sending Last-Event-ID from lastEventId(); it does
 * not reconnect after 401/403/404 or when onEnd() returns false for a stream the server ended.
 *
 * Callbacks: onEvent({ type, data, id }), onState(state, detail) with state 'connecting' | 'open' | 'retrying' |
 * 'failed' | 'closed'. While a stream is not 'open', the caller polls.
 */
export class EventStream {
  constructor(path, { onEvent, onState = () => {}, lastEventId = () => null, onEnd = () => true }) {
    Object.assign(this, { path, onEvent, onState, lastEventId, onEnd });
    this.state = 'closed';
    this.attempt = 0;
    this.stopped = false;
    this.nodeId = null; // X-Sdlc-Node-Id: the node whose commits this stream carries
    this.ctl = null;
    this.retryTimer = 0;
    this.watchdog = 0;
    this.lastData = 0;
  }

  start() {
    this.stopped = false;
    this.connect();
    return this;
  }

  close() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    clearInterval(this.watchdog);
    this.ctl?.abort();
    this.setState('closed');
  }

  get open() {
    return this.state === 'open';
  }

  setState(state, detail = null) {
    if (this.state === state && !detail) return;
    this.state = state;
    this.onState(state, detail);
  }

  async connect() {
    if (this.stopped) return;
    clearTimeout(this.retryTimer);
    const ctl = new AbortController();
    this.ctl = ctl;
    this.setState('connecting');
    const headers = { Accept: 'text/event-stream', ...authHeaders() };
    const last = this.lastEventId();
    if (last != null && last !== '' && Number(last) > 0) headers['Last-Event-ID'] = String(last);
    let res;
    try {
      res = await fetch(this.path, { headers, signal: ctl.signal, cache: 'no-store' });
    } catch {
      if (!this.stopped && ctl === this.ctl) this.retry();
      return;
    }
    if (this.stopped || ctl !== this.ctl) return;
    const contentType = res.headers.get('content-type') ?? '';
    if (!res.ok || !res.body || !contentType.includes('text/event-stream')) {
      const error = toApiError(res, parseJson(await res.text().catch(() => '')));
      const fatal = [401, 403, 404].includes(res.status);
      this.setState(fatal ? 'failed' : 'retrying', error);
      if (fatal) {
        this.stopped = true;
        return;
      }
      const retryAfter = Number(res.headers.get('retry-after'));
      this.retry(Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : undefined);
      return;
    }
    reachabilityListener(true);
    this.attempt = 0;
    this.nodeId = res.headers.get('x-sdlc-node-id');
    this.lastData = Date.now();
    this.setState('open');
    clearInterval(this.watchdog);
    this.watchdog = setInterval(() => {
      if (Date.now() - this.lastData > SILENCE_MS) ctl.abort(); // reconnects below
    }, SILENCE_MS / 3);
    const feed = sseParser((event) => {
      try {
        this.onEvent(event);
      } catch (err) {
        console.error(err); // a render bug must not break the stream
      }
    });
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let ended = false;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          ended = true;
          break;
        }
        this.lastData = Date.now();
        feed(decoder.decode(value, { stream: true }));
      }
    } catch {
      /* aborted or the connection broke */
    } finally {
      clearInterval(this.watchdog);
    }
    if (this.stopped || ctl !== this.ctl) return;
    if (ended && !this.onEnd()) {
      this.stopped = true;
      this.setState('closed');
      return;
    }
    this.retry();
  }

  retry(delayMs) {
    if (this.stopped) return;
    const base = delayMs ?? RETRY_MS[Math.min(this.attempt, RETRY_MS.length - 1)];
    this.attempt += 1;
    this.setState('retrying');
    this.retryTimer = setTimeout(() => this.connect(), base + Math.round(Math.random() * 250));
  }
}
