// Thin fetch wrapper: attaches the in-memory access token, refreshes it once on 401, and turns
// RFC 9457 problem responses into ApiError. The access token never touches localStorage.

import type { AuthResponse } from "./types";

export type ProblemBody = {
  detail?: string;
  errors?: Record<string, string>;
  duplicates?: { id: number; fullName: string; phone: string; neetRollNo: string | null; status: string }[];
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
