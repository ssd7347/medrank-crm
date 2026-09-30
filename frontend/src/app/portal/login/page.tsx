"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Loading, cx } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { usePortal } from "@/lib/portal";

export default function PortalLoginPage() {
  const { displayName, loading, login, activate } = usePortal();
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "activate">("login");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [idleNotice, setIdleNotice] = useState(false);

  useEffect(() => {
    let idle = false;
    try {
      idle = window.sessionStorage.getItem("crm:portal:idle") === "1";
      window.sessionStorage.removeItem("crm:portal:idle");
    } catch {
      // Storage blocked: skip the notice.
    }
    // Only ever switch it on: in development React runs this twice and the flag is gone the second time.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (idle) setIdleNotice(true);
  }, []);

  useEffect(() => {
    if (!loading && displayName) router.replace("/portal");
  }, [loading, displayName, router]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (mode === "activate" && password !== confirm) {
      setError("The two passwords do not match.");
      return;
    }
    setSubmitting(true);
    try {
      if (mode === "login") await login(phone.trim(), password);
      else await activate(phone.trim(), code.trim(), password);
      router.replace("/portal");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading || displayName) {
    return (
      <main className="min-h-screen">
        <Loading />
      </main>
    );
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-muted px-4 py-10">
      <div className="w-full max-w-sm rounded-2xl border border-line bg-surface p-6 shadow-sm">
        <Logo />
        <h1 className="mt-6 text-xl font-semibold tracking-tight">Student & parent portal</h1>
        <p className="mt-1 text-sm text-ink-soft">See your counselling status, deadlines, documents and fees.</p>

        <div className="mt-5 grid grid-cols-2 gap-1 rounded-lg bg-muted p-1 text-sm" role="tablist">
          {(["login", "activate"] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={mode === m}
              onClick={() => {
                setMode(m);
                setError(null);
                setPassword("");
                setConfirm("");
              }}
              className={cx("rounded-md px-2 py-1.5", mode === m ? "bg-surface font-medium shadow-sm" : "text-ink-soft")}
            >
              {m === "login" ? "Sign in" : "First time here"}
            </button>
          ))}
        </div>

        <form onSubmit={onSubmit} className="mt-5 space-y-4">
          {error && <Alert>{error}</Alert>}
          {idleNotice && !error && <Alert tone="amber">You were signed out after 3 minutes without activity. Please sign in again.</Alert>}
          <Field label="Mobile number" required hint="The number you gave your counsellor">
            {(id) => <Input id={id} type="tel" inputMode="tel" autoComplete="username" required value={phone} onChange={(e) => setPhone(e.target.value)} />}
          </Field>
          {mode === "activate" && (
            <Field label="One-time code" required hint="8 letters and numbers, from your counsellor">
              {(id) => (
                <Input
                  id={id}
                  required
                  autoComplete="one-time-code"
                  autoCapitalize="characters"
                  maxLength={12}
                  value={code}
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                  className="font-mono tracking-widest"
                />
              )}
            </Field>
          )}
          <Field label={mode === "login" ? "Password" : "Choose a password"} required hint={mode === "activate" ? "At least 8 characters" : undefined}>
            {(id) => (
              <Input
                id={id}
                type="password"
                autoComplete={mode === "login" ? "current-password" : "new-password"}
                required
                minLength={mode === "activate" ? 8 : undefined}
                maxLength={72}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            )}
          </Field>
          {mode === "activate" && (
            <Field label="Type the password again" required>
              {(id) => <Input id={id} type="password" autoComplete="new-password" required maxLength={72} value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
            </Field>
          )}
          <Button type="submit" loading={submitting} className="w-full">
            {mode === "login" ? "Sign in" : "Set password and sign in"}
          </Button>
        </form>
        <p className="mt-5 text-xs text-ink-faint">
          {mode === "login" ? "Forgot your password? Ask your counsellor for a new one-time code, then use “First time here”." : "No code? Ask your counsellor to give you portal access."}
        </p>
      </div>
    </main>
  );
}
