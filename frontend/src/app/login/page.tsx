"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Loading } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { useAuth, type OtpSent } from "@/lib/auth";

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
      idle = window.sessionStorage.getItem("crm:staff:idle") === "1";
      window.sessionStorage.removeItem("crm:staff:idle");
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
    } finally {
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
      <Field label="Mobile number" required hint={sent ? undefined : "The number your admin registered for you"}>
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
            <div className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2.5 text-sm text-amber-900" role="status">
              <p>Your one-time code is</p>
              <p className="my-1 font-mono text-2xl font-semibold tracking-[0.3em]">{sent.codeOnScreen}</p>
              <p className="text-xs">Shown here only until WhatsApp delivery is connected. It works once, for {Math.round(sent.validForSeconds / 60)} minutes.</p>
            </div>
          ) : sent.shownOnScreen ? (
            <Alert tone="amber">This number is not registered for staff login. Check the number or ask your admin to add it.</Alert>
          ) : (
            <Alert tone="blue">If this number is registered, a one-time code has been sent to it.</Alert>
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
            className="text-brand-700 hover:underline"
            onClick={() => {
              setSent(null);
              setCode("");
              setError(null);
            }}
          >
            Change number
          </button>
          <button type="button" className="text-brand-700 hover:underline" onClick={sendCode} disabled={submitting}>
            Get a new code
          </button>
        </div>
      )}
    </form>
  );
}

export default function LoginPage() {
  return (
    <main className="grid min-h-screen lg:grid-cols-2">
      <div className="hidden flex-col justify-between bg-brand-800 p-10 text-brand-50 lg:flex">
        <Logo light />
        <div className="max-w-md">
          <p className="text-3xl leading-tight font-semibold tracking-tight">
            Every NEET aspirant, every round, every deadline — in one place.
          </p>
          <p className="mt-4 text-brand-200">
            Leads, NEET profiles, college and seat-matrix data for AIQ, State, Management and NRI counselling.
          </p>
        </div>
        <p className="text-xs text-brand-300">Staff access only. All actions are logged.</p>
      </div>
      <div className="flex items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <Logo />
          </div>
          <h1 className="text-xl font-semibold tracking-tight">Log in</h1>
          <p className="mt-1 mb-6 text-sm text-ink-soft">Enter your mobile number to get a one-time code.</p>
          <Suspense fallback={<Loading />}>
            <LoginForm />
          </Suspense>
        </div>
      </div>
    </main>
  );
}
