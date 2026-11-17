"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Loading } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { useAuth, type OtpSent } from "@/lib/auth";
import { useBranding } from "@/lib/branding";

// The login page for staff: a mobile number and a one-time code. Accounts are created by the admin.

function LoginForm() {
  const { user, loading, requestOtp, verifyOtp } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  // Set once a code has been requested; its presence switches the form to the second step.
  const [sent, setSent] = useState<OtpSent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [idleNotice, setIdleNotice] = useState(false);

  useEffect(() => {
    let idle = false;
    try {
      idle = window.sessionStorage.getItem("crm:idle") === "1";
      window.sessionStorage.removeItem("crm:idle");
    } catch {
      // Storage blocked: skip the notice.
    }
    // Only ever switch it on: in development React runs this twice and the flag is gone the second time.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (idle) setIdleNotice(true);
  }, []);

  useEffect(() => {
    if (!loading && user) router.replace("/dashboard");
  }, [loading, user, router]);

  async function sendCode() {
    setError(null);
    setSubmitting(true);
    try {
      setSent(await requestOtp(phone.trim()));
      setCode("");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!sent) {
      await sendCode();
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await verifyOtp(phone.trim(), code.trim());
      router.replace("/dashboard");
    } catch (err) {
      setError(errorMessage(err));
      setSubmitting(false);
    }
  }

  if (loading || user) return <Loading />;

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      {(idleNotice || params.get("expired")) && !error && !sent && (
        <Alert tone="amber">{idleNotice ? "You were signed out after 3 minutes without activity. Please log in again." : "Your session expired. Please log in again."}</Alert>
      )}
      {error && <Alert>{error}</Alert>}
      <Field label="Mobile number" required hint={sent ? undefined : "Staff accounts are created by the admin"}>
        {(id) => (
          <Input
            id={id}
            type="tel"
            inputMode="numeric"
            autoComplete="tel"
            required
            disabled={!!sent}
            maxLength={16}
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
        )}
      </Field>

      {sent && (
        <>
          {sent.codeOnScreen ? (
            <div className="rounded-md border border-brand-300 bg-brand-50 px-4 py-3.5 text-sm text-ink-soft" role="status">
              <p className="eyebrow text-brand-700">Your one-time code</p>
              <p className="my-2 font-mono text-3xl font-semibold tracking-[0.35em] text-brand-800">{sent.codeOnScreen}</p>
              <p className="text-xs leading-relaxed text-ink-faint">Shown here only until SMS delivery is connected. It works once, for {Math.round(sent.validForSeconds / 60)} minutes.</p>
            </div>
          ) : sent.shownOnScreen ? (
            <Alert tone="amber">There is no account for this number. Ask the admin to create one for you.</Alert>
          ) : (
            <Alert tone="blue">If this number has an ID, a one-time code has been sent to it by SMS. It works once, for {Math.round(sent.validForSeconds / 60)} minutes.</Alert>
          )}
          <Field label="One-time code" required>
            {(id) => (
              <Input
                id={id}
                inputMode="numeric"
                autoComplete="one-time-code"
                required
                autoFocus
                maxLength={6}
                pattern="[0-9]{6}"
                title="6 digits"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                className="font-mono tracking-widest"
              />
            )}
          </Field>
        </>
      )}

      <Button type="submit" loading={submitting} className="w-full">
        {sent ? "Log in" : "Get one-time code"}
      </Button>
      {sent && (
        <div className="flex justify-between text-sm">
          <button
            type="button"
            className="text-brand-700 underline-offset-4 transition hover:text-brand-800 hover:underline"
            onClick={() => {
              setSent(null);
              setCode("");
              setError(null);
            }}
          >
            Change number
          </button>
          <button type="button" className="text-brand-700 underline-offset-4 transition hover:text-brand-800 hover:underline" onClick={sendCode} disabled={submitting}>
            Get a new code
          </button>
        </div>
      )}
    </form>
  );
}

export default function LoginPage() {
  const { companyName, agentName, loginHeadline, loginText } = useBranding();
  return (
    <main className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      <div className="relative hidden flex-col justify-between overflow-hidden bg-gradient-to-br from-navy via-brand-800 to-brand-700 p-12 text-white lg:flex">
        {/* Deep royal blue with a soft glow and a fine silver frame: the brand's quiet signature. */}
        <div aria-hidden className="pointer-events-none absolute -top-40 -right-40 h-[28rem] w-[28rem] rounded-full bg-brand-400/30 blur-3xl" />
        <div aria-hidden className="pointer-events-none absolute inset-6 rounded-lg border border-white/15" />
        <div className="relative">
          <Logo onDark />
        </div>
        <div className="relative max-w-lg">
          <div className="mb-6 h-px w-16 bg-white/60" />
          <p className="font-display text-[2.75rem] leading-[1.1] font-semibold tracking-wide text-white">{loginHeadline || "Every customer reached, every call followed up."}</p>
          <p className="mt-6 text-[0.95rem] leading-relaxed text-white/80">
            {loginText || `${agentName}, the AI assistant of ${companyName}, makes the routine calls. Your team takes over whenever a person is needed.`}
          </p>
        </div>
        <p className="eyebrow relative text-white/60">Private and secure · Every action is logged</p>
      </div>
      <div className="flex items-center justify-center px-5 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-10 lg:hidden">
            <Logo />
          </div>
          <p className="eyebrow text-brand-700">Welcome back</p>
          <h1 className="mt-2 font-display text-4xl font-semibold tracking-wide">Sign in</h1>
          <div className="gold-rule mt-4 mb-5" />
          <p className="mb-7 text-sm leading-relaxed text-ink-soft">Enter your mobile number to get a one-time code.</p>
          <Suspense fallback={<Loading />}>
            <LoginForm />
          </Suspense>
        </div>
      </div>
    </main>
  );
}
