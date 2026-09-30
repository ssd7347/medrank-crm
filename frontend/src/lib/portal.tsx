"use client";

// Session and API client for the student & parent portal. Deliberately separate from the staff client in
// api.ts: its own in-memory token, its own refresh cookie, and it only ever calls /api/portal/*.

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { ApiError, type ProblemBody } from "./api";
import type { Category, Course, Quota } from "./types";
import { IdleWarning, useIdleLogout } from "./use-idle";

type Session = { accessToken: string; expiresIn: number; displayName: string };

let token: string | null = null;
let refreshInFlight: Promise<Session | null> | null = null;
let onSessionLost: (() => void) | null = null;

function refresh(): Promise<Session | null> {
  if (!refreshInFlight) {
    refreshInFlight = fetch("/api/portal/auth/refresh", { method: "POST", credentials: "same-origin" })
      .then(async (res) => {
        if (!res.ok) return null;
        const s = (await res.json()) as Session;
        token = s.accessToken;
        return s;
      })
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

export async function portalApi<T>(path: string, opts: { method?: "GET" | "POST"; body?: unknown; form?: FormData } = {}): Promise<T> {
  const doFetch = () => {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    let body: BodyInit | undefined;
    if (opts.form) {
      body = opts.form;
    } else if (opts.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(opts.body);
    }
    return fetch(path, { method: opts.method ?? (body ? "POST" : "GET"), headers, body, credentials: "same-origin" });
  };
  let res = await doFetch();
  if (res.status === 401 && !path.startsWith("/api/portal/auth/")) {
    if (await refresh()) res = await doFetch();
    if (res.status === 401) {
      token = null;
      onSessionLost?.();
    }
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { detail: text.slice(0, 200) };
    }
  }
  if (!res.ok) throw new ApiError(res.status, (data as ProblemBody) ?? {});
  return data as T;
}

type PortalState = {
  displayName: string | null;
  /** True until the first silent refresh has finished. */
  loading: boolean;
  login: (phone: string, password: string) => Promise<void>;
  activate: (phone: string, code: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const PortalContext = createContext<PortalState | null>(null);

export function PortalProvider({ children }: { children: React.ReactNode }) {
  const [displayName, setDisplayName] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    refresh().then((s) => {
      setDisplayName(s?.displayName ?? null);
      setLoading(false);
    });
    onSessionLost = () => setDisplayName(null);
    return () => {
      onSessionLost = null;
    };
  }, []);

  const open = useCallback((s: Session) => {
    token = s.accessToken;
    setDisplayName(s.displayName);
  }, []);
  const login = useCallback(async (phone: string, password: string) => open(await portalApi<Session>("/api/portal/auth/login", { body: { phone, password } })), [open]);
  const activate = useCallback(
    async (phone: string, code: string, password: string) => open(await portalApi<Session>("/api/portal/auth/activate", { body: { phone, code, password } })),
    [open],
  );
  const logout = useCallback(async () => {
    try {
      await portalApi("/api/portal/auth/logout", { method: "POST" });
    } finally {
      token = null;
      setDisplayName(null);
    }
  }, []);

  // Families often use shared phones: sign out after 3 minutes without activity (the server enforces it too).
  const secondsLeft = useIdleLogout({
    enabled: !!displayName,
    storageKey: "crm:portal",
    keepAlive: refresh,
    onIdle: () => {
      portalApi("/api/portal/auth/logout", { method: "POST" })
        .catch(() => undefined)
        .finally(() => {
          token = null;
          try {
            window.sessionStorage.setItem("crm:portal:idle", "1");
          } catch {
            // The login page just will not show the reason.
          }
          // The portal page sends signed-out visitors to the login page.
          setDisplayName(null);
        });
    },
  });

  const value = useMemo(() => ({ displayName, loading, login, activate, logout }), [displayName, loading, login, activate, logout]);
  return (
    <PortalContext.Provider value={value}>
      {children}
      <IdleWarning secondsLeft={secondsLeft} />
    </PortalContext.Provider>
  );
}

export function usePortal() {
  const ctx = useContext(PortalContext);
  if (!ctx) throw new Error("usePortal must be used inside PortalProvider");
  return ctx;
}

// ---- DTOs (mirror PortalService)

export type PortalMe = {
  displayName: string;
  phone: string;
  orgName: string;
  students: { id: number; fullName: string; relation: "STUDENT" | "PARENT" }[];
};

export type PortalDocuments = {
  requiredCount: number;
  requiredDone: number;
  items: { typeId: number; name: string; required: boolean; status: string; validUntil: string | null; expired: boolean; files: number }[];
};

export type PortalTicket = { id: number; subject: string; category: string; status: string; createdAt: string; resolution: string | null };

export type PortalOverview = {
  student: {
    id: number;
    fullName: string;
    category: Category;
    homeState: string;
    neetScore: number | null;
    neetAir: number | null;
    counsellorName: string | null;
    counsellorPhone: string | null;
  };
  nextDeadline: { title: string; detail: string; at: string } | null;
  tracks: {
    authorityName: string;
    authorityType: string;
    academicYear: number;
    status: string;
    registrationNo: string | null;
    currentRound: string | null;
    currentPhase: string | null;
    allotments: {
      roundLabel: string;
      allotted: boolean;
      collegeName: string | null;
      course: Course | null;
      quota: Quota | null;
      decision: string | null;
      decisionDeadline: string | null;
    }[];
  }[];
  shortlist: { collegeName: string; state: string; course: Course; quota: Quota; band: string | null }[];
  documents: PortalDocuments;
  fees: {
    total: number;
    paid: number;
    balance: number;
    instalments: { label: string; amount: number; dueDate: string; balance: number; state: string }[];
    payments: { receiptNo: string; amount: number; paidOn: string; method: string }[];
  };
  tickets: PortalTicket[];
  loans: { lender: string; amountRequested: number; amountSanctioned: number | null; status: string; neededBy: string | null; risk: string }[];
  sessions: { id: number; topic: string; mode: string; scheduledAt: string; durationMinutes: number; meetingUrl: string | null; hostName: string | null }[];
  agreements: PortalAgreement[];
};

export type PortalAgreement = { id: number; title: string; status: "PENDING" | "SIGNED"; body: string; signerName: string | null; signedAt: string | null };
