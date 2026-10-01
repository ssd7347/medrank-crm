"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { api, refreshSession, setAccessToken, setSessionLostHandler } from "./api";
import type { Role, User } from "./types";
import { IdleWarning, useIdleLogout } from "./use-idle";

/** `codeOnScreen` is filled only while codes are shown on the login page instead of being sent (temporary). */
export type OtpSent = { validForSeconds: number; codeOnScreen: string | null; shownOnScreen: boolean };

type LoginResult = { kind: "STAFF"; accessToken: string; expiresIn: number; user: User | null };

type AuthState = {
  user: User | null;
  /** True until the initial silent refresh has finished. */
  loading: boolean;
  /** Step 1 of sign-in: asks for a one-time code for this mobile number. */
  requestOtp: (phone: string) => Promise<OtpSent>;
  /** Step 2 of sign-in. */
  verifyOtp: (phone: string, code: string) => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
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

  const requestOtp = useCallback((phone: string) => api<OtpSent>("/api/auth/otp/request", { body: { phone } }), []);

  const verifyOtp = useCallback(async (phone: string, code: string) => {
    const res = await api<LoginResult>("/api/auth/otp/verify", { body: { phone, code } });
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

  // Signed out after 3 minutes without activity; the server enforces the same limit.
  const secondsLeft = useIdleLogout({
    enabled: !!user,
    storageKey: "crm:staff",
    keepAlive: refreshSession,
    onIdle: () => {
      api("/api/auth/logout", { method: "POST" })
        .catch(() => undefined)
        .finally(() => {
          setAccessToken(null);
          try {
            window.sessionStorage.setItem("crm:idle", "1");
          } catch {
            // The login page just will not show the reason.
          }
          // The app layout sends signed-out visitors to the login page.
          setUser(null);
        });
    },
  });

  const hasRole = useCallback((...roles: Role[]) => !!user && roles.includes(user.role), [user]);

  const value = useMemo(() => ({ user, loading, requestOtp, verifyOtp, logout, hasRole }), [user, loading, requestOtp, verifyOtp, logout, hasRole]);
  return (
    <AuthContext.Provider value={value}>
      {children}
      <IdleWarning secondsLeft={secondsLeft} />
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}

/** Admins and managers see every contact and can import; agents work their own. */
export const MANAGER_ROLES: Role[] = ["SUPER_ADMIN", "MANAGER"];
