// Tiny fetch wrapper: JSON in and out, a timeout on every request (AbortController),
// optional cancellation by the caller, and RFC 9457 Problem Details mapped to ApiError.
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
 */
export async function api(path, { method = 'GET', body, operatorId, signal, timeoutMs = 10_000 } = {}) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forwardAbort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', forwardAbort, { once: true });

  const headers = { Accept: 'application/json, application/problem+json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && operatorId) headers['X-Operator-Id'] = operatorId;
  const apiKey = store.get('sdlc.apiKey');
  if (apiKey) headers['X-Api-Key'] = apiKey;
  const accessToken = sessionValue('sdlc.accessToken'); // oidc mode: an access token obtained from the identity provider
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  try {
    const res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: 'no-store',
    });
    const parsed = parseJson(await res.text());
    reachabilityListener(true);
    if (!res.ok) throw toApiError(res, parsed);
    return parsed;
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
