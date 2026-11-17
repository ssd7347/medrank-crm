// Thin fetch wrapper: attaches the in-memory access token, refreshes it once on 401, and turns
// RFC 9457 problem responses into ApiError. The access token never touches localStorage.

import type { AuthResponse } from "./types";

export type ProblemBody = {
  detail?: string;
  errors?: Record<string, string>;
  existingContactId?: number;
  canOverride?: boolean;
  [key: string]: unknown;
};

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: ProblemBody,
  ) {
    super(body.detail || `Request failed (${status})`);
  }
}

let accessToken: string | null = null;
let refreshInFlight: Promise<AuthResponse | null> | null = null;
let onSessionLost: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function setSessionLostHandler(handler: () => void) {
  onSessionLost = handler;
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<AuthResponse | null> {
  if (!refreshInFlight) {
    refreshInFlight = fetch("/api/auth/refresh", { method: "POST", credentials: "same-origin" })
      .then(async (res) => {
        if (!res.ok) return null;
        const data = (await res.json()) as AuthResponse;
        accessToken = data.accessToken;
        return data;
      })
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

type Query = Record<string, string | number | boolean | null | undefined>;

type Options = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  query?: Query;
  form?: FormData;
};

export function buildUrl(path: string, query?: Query) {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v !== undefined && v !== null && v !== "") params.set(k, String(v));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const doFetch = () => {
    const headers: Record<string, string> = {};
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
    let body: BodyInit | undefined;
    if (opts.form) {
      body = opts.form;
    } else if (opts.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(opts.body);
    }
    return fetch(buildUrl(path, opts.query), {
      method: opts.method ?? (body ? "POST" : "GET"),
      headers,
      body,
      credentials: "same-origin",
    });
  };

  let res = await doFetch();
  if (res.status === 401 && !path.startsWith("/api/auth/")) {
    const refreshed = await refreshSession();
    if (refreshed) {
      res = await doFetch();
    }
    if (res.status === 401) {
      accessToken = null;
      onSessionLost?.();
    }
  }

  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    throw new ApiError(res.status, (data as ProblemBody) ?? {});
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text.slice(0, 200) };
  }
}

/** A user-facing message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status >= 500) return "The server had a problem. Please try again.";
    return e.message;
  }
  if (e instanceof TypeError) return "Cannot reach the server. Check your connection.";
  return "Something went wrong.";
}

/** Fetches a protected file (e.g. a document scan) and opens it in a new tab via a temporary object URL. */
export async function openProtectedFile(path: string) {
  const win = window.open("", "_blank");
  const load = () => fetch(path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: "same-origin" });
  let res = await load();
  if (res.status === 401 && (await refreshSession())) res = await load();
  if (!res.ok) {
    win?.close();
    throw new ApiError(res.status, { detail: res.status === 403 || res.status === 404 ? "You cannot open this file" : "Could not open the file" });
  }
  const url = URL.createObjectURL(await res.blob());
  if (win) win.location.href = url;
  else window.location.href = url;
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Downloads a protected file (e.g. a CSV export) under the given name. */
export async function downloadProtected(path: string, fileName: string) {
  const url = URL.createObjectURL(await getProtectedBlob(path));
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** GETs binary data (a file, a recording) from a protected endpoint, signed in. */
export async function getProtectedBlob(path: string): Promise<Blob> {
  const load = () => fetch(path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: "same-origin" });
  let res = await load();
  if (res.status === 401 && (await refreshSession())) res = await load();
  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(res.status, text ? safeJson(text) as ProblemBody : {});
  }
  return res.blob();
}

/** POSTs JSON to a protected endpoint that answers with binary data (e.g. synthesized speech) and returns it. */
export async function postForBlob(path: string, body: unknown): Promise<Blob> {
  const load = () =>
    fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
      body: JSON.stringify(body),
      credentials: "same-origin",
    });
  let res = await load();
  if (res.status === 401 && (await refreshSession())) res = await load();
  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(res.status, text ? (safeJson(text) as ProblemBody) : {});
  }
  return res.blob();
}
