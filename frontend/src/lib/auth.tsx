"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { api, refreshSession, setAccessToken, setSessionLostHandler } from "./api";
import type { AuthResponse, Role, User } from "./types";

type AuthState = {
  user: User | null;
  /** True until the initial silent refresh has finished. */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // The family portal has its own session (see lib/portal.ts); staff auth stays out of its way.
    if (window.location.pathname.startsWith("/portal") || window.location.pathname.startsWith("/ask")) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setLoading(false);
      return;
    }
    // A page reload loses the in-memory access token; the refresh cookie gets a new one.
    refreshSession().then((session) => {
      setUser(session?.user ?? null);
      setLoading(false);
    });
    setSessionLostHandler(() => {
      setUser(null);
      router.replace("/login?expired=1");
    });
  }, [router]);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api<AuthResponse>("/api/auth/login", { body: { email, password } });
    setAccessToken(res.accessToken);
    setUser(res.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api("/api/auth/logout", { method: "POST" });
    } finally {
      setAccessToken(null);
      setUser(null);
      router.replace("/login");
    }
  }, [router]);

  const hasRole = useCallback((...roles: Role[]) => !!user && roles.includes(user.role), [user]);

  const value = useMemo(() => ({ user, loading, login, logout, hasRole }), [user, loading, login, logout, hasRole]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}

/** Roles that work the lead pipeline. */
export const LEAD_ROLES: Role[] = ["SUPER_ADMIN", "COUNSELLOR", "TELECALLER"];
/** Roles that may view student profiles (counsellors see only their own). */
export const STUDENT_ROLES: Role[] = [
  "SUPER_ADMIN",
  "COUNSELLOR",
  "DOCUMENTATION_EXEC",
  "ACCOUNTANT",
  "LOAN_DESK",
  "GRIEVANCE_OFFICER",
];
/** Roles that may propose changes to college data. */
export const DATA_ROLES: Role[] = ["SUPER_ADMIN", "DATA_EXEC"];
/** Roles that manage the document checklist (counsellors: own students). */
export const DOCUMENT_ROLES: Role[] = ["SUPER_ADMIN", "DOCUMENTATION_EXEC", "COUNSELLOR"];
/** Roles that can see consultancy fees (counsellors: own students). */
export const FEE_READ_ROLES: Role[] = ["SUPER_ADMIN", "ACCOUNTANT", "LOAN_DESK", "COUNSELLOR"];
/** Roles that record money. */
export const FEE_WRITE_ROLES: Role[] = ["SUPER_ADMIN", "ACCOUNTANT"];
/** Roles that manage the grievance register. */
export const GRIEVANCE_ROLES: Role[] = ["SUPER_ADMIN", "GRIEVANCE_OFFICER"];
