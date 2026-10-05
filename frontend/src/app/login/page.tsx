"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Loading, Select, cx } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth, type OtpSent } from "@/lib/auth";
import { INDIAN_STATES, label } from "@/lib/format";
import { CATEGORIES, LANGUAGES } from "@/lib/types";

// The one login page for everyone. The mobile number decides where you land: the admin and staff open the
// CRM, students and parents open their portal.

function LoginForm({ startPhone }: { startPhone: string }) {
  const { user, loading, requestOtp, verifyOtp } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const [phone, setPhone] = useState(startPhone);
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
      const kind = await verifyOtp(phone.trim(), code.trim());
      router.replace(kind === "PORTAL" ? "/portal" : "/dashboard");
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
      <Field label="Mobile number" required hint={sent ? undefined : "Admin, staff and students all log in here with their own number"}>
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
              <p className="my-2 font-mono text-3xl font-semibold tracking-[0.35em] text-brand-900">{sent.codeOnScreen}</p>
              <p className="text-xs leading-relaxed text-ink-faint">Shown here only until WhatsApp delivery is connected. It works once, for {Math.round(sent.validForSeconds / 60)} minutes.</p>
            </div>
          ) : sent.shownOnScreen ? (
            <Alert tone="amber">This number has no ID yet. Students can create one with Register; staff IDs are created by the admin.</Alert>
          ) : (
            <Alert tone="blue">If this number has an ID, a one-time code has been sent to it.</Alert>
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

type Registered = { studentId: string | null; fullName: string };

function RegisterForm({ onLogin }: { onLogin: (phone: string) => void }) {
  const [v, setV] = useState({
    fullName: "",
    phone: "",
    email: "",
    category: "",
    homeState: "",
    neetScore: "",
    neetAir: "",
    parentName: "",
    parentPhone: "",
    language: "ENGLISH",
    website: "",
  });
  const [done, setDone] = useState<Registered | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setV((p) => ({ ...p, [k]: e.target.value }));

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      setDone(
        await api<Registered>("/api/auth/register", {
          body: {
            ...v,
            email: v.email || null,
            neetScore: v.neetScore === "" ? null : Number(v.neetScore),
            neetAir: v.neetAir === "" ? null : Number(v.neetAir),
            parentName: v.parentName || null,
            parentPhone: v.parentPhone || null,
          },
        }),
      );
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <div className="space-y-4">
        <Alert tone="green">
          <p className="font-medium">Your ID has been created, {done.fullName}.</p>
          {done.studentId && (
            <p className="mt-1">
              Student ID: <span className="font-mono font-semibold">{done.studentId}</span>
            </p>
          )}
          <p className="mt-1">You can log in now with your mobile number. A counsellor will contact you soon.</p>
        </Alert>
        <Button className="w-full" onClick={() => onLogin(v.phone)}>
          Log in
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Student's full name" required error={fieldErrors.fullName}>
        {(id) => <Input id={id} required maxLength={120} autoComplete="name" value={v.fullName} onChange={set("fullName")} />}
      </Field>
      <Field label="Mobile number" required error={fieldErrors.phone} hint="10 digits. You will log in with this number.">
        {(id) => <Input id={id} type="tel" inputMode="numeric" autoComplete="tel" required maxLength={16} value={v.phone} onChange={set("phone")} />}
      </Field>
      <Field label="Email" error={fieldErrors.email}>
        {(id) => <Input id={id} type="email" autoComplete="email" maxLength={160} value={v.email} onChange={set("email")} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Category" required error={fieldErrors.category}>
          {(id) => <Select id={id} required value={v.category} onChange={set("category")} options={CATEGORIES} placeholder="Choose…" />}
        </Field>
        <Field label="Home state" required error={fieldErrors.homeState}>
          {(id) => <Select id={id} required value={v.homeState} onChange={set("homeState")} options={INDIAN_STATES} placeholder="Choose…" />}
        </Field>
        <Field label="NEET score" error={fieldErrors.neetScore} hint="If you know it">
          {(id) => <Input id={id} type="number" inputMode="numeric" min={-180} max={720} value={v.neetScore} onChange={set("neetScore")} />}
        </Field>
        <Field label="All India Rank" error={fieldErrors.neetAir} hint="If you know it">
          {(id) => <Input id={id} type="number" inputMode="numeric" min={1} value={v.neetAir} onChange={set("neetAir")} />}
        </Field>
        <Field label="Parent's name" error={fieldErrors.parentName}>
          {(id) => <Input id={id} maxLength={120} value={v.parentName} onChange={set("parentName")} />}
        </Field>
        <Field label="Parent's mobile" error={fieldErrors.parentPhone}>
          {(id) => <Input id={id} type="tel" inputMode="numeric" maxLength={16} value={v.parentPhone} onChange={set("parentPhone")} />}
        </Field>
      </div>
      <Field label="Preferred language">{(id) => <Select id={id} value={v.language} onChange={set("language")} options={LANGUAGES} labelFor={label} />}</Field>
      {/* Hidden from people; bots that fill every field give themselves away. */}
      <div className="hidden" aria-hidden>
        <label>
          Website
          <input tabIndex={-1} autoComplete="off" value={v.website} onChange={set("website")} />
        </label>
      </div>
      <p className="text-xs text-ink-faint">By registering you agree to be contacted by phone or WhatsApp about admission counselling.</p>
      <Button type="submit" loading={submitting} className="w-full">
        Create my student ID
      </Button>
    </form>
  );
}

export default function LoginPage() {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [startPhone, setStartPhone] = useState("");
  return (
    <main className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      <div className="relative hidden flex-col justify-between overflow-hidden border-r border-line bg-gradient-to-br from-surface via-brand-50 to-brand-100 p-12 lg:flex">
        {/* A soft gold glow and a fine frame: the brand's quiet signature. */}
        <div aria-hidden className="pointer-events-none absolute -top-40 -right-40 h-[28rem] w-[28rem] rounded-full bg-brand-300/40 blur-3xl" />
        <div aria-hidden className="pointer-events-none absolute inset-6 rounded-lg border border-brand-600/30" />
        <div className="relative">
          <Logo light />
        </div>
        <div className="relative max-w-lg">
          <div className="mb-6 h-px w-16 bg-brand-600" />
          <p className="font-display text-[2.75rem] leading-[1.1] font-semibold tracking-wide text-navy">
            Every NEET aspirant, every round, every deadline — in one place.
          </p>
          <p className="mt-6 text-[0.95rem] leading-relaxed text-ink-soft">
            Leads, NEET profiles, college and seat-matrix data for AIQ, State, Management and NRI counselling.
          </p>
        </div>
        <p className="eyebrow relative text-brand-800">Private and secure · Every action is logged</p>
      </div>
      <div className="flex items-center justify-center px-5 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-10 lg:hidden">
            <Logo />
          </div>
          <div className="mb-8 grid grid-cols-2 gap-1 rounded-md border border-line bg-muted p-1 text-sm" role="tablist">
            {(["login", "register"] as const).map((m) => (
              <button
                key={m}
                type="button"
                role="tab"
                aria-selected={mode === m}
                onClick={() => setMode(m)}
                className={cx("rounded px-2 py-2 transition-colors", mode === m ? "bg-surface font-semibold text-ink shadow-card ring-1 ring-brand-300" : "text-ink-soft hover:text-ink")}
              >
                {m === "login" ? "Login" : "Register"}
              </button>
            ))}
          </div>
          {mode === "login" ? (
            <>
              <p className="eyebrow text-brand-700">Welcome back</p>
              <h1 className="mt-2 font-display text-4xl font-semibold tracking-wide">Sign in</h1>
              <div className="gold-rule mt-4 mb-5" />
              <p className="mb-7 text-sm leading-relaxed text-ink-soft">Enter your mobile number to get a one-time code.</p>
              <Suspense fallback={<Loading />}>
                <LoginForm key={startPhone} startPhone={startPhone} />
              </Suspense>
            </>
          ) : (
            <>
              <p className="eyebrow text-brand-700">Students and parents</p>
              <h1 className="mt-2 font-display text-4xl font-semibold tracking-wide">Register</h1>
              <div className="gold-rule mt-4 mb-5" />
              <p className="mb-7 text-sm leading-relaxed text-ink-soft">
                For students and parents. Staff do not register here: the admin creates staff IDs.
              </p>
              <RegisterForm
                onLogin={(phone) => {
                  setStartPhone(phone);
                  setMode("login");
                }}
              />
            </>
          )}
        </div>
      </div>
    </main>
  );
}
