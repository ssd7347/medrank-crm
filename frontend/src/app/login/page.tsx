"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Loading } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";

function LoginForm() {
  const { user, loading, login } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
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

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email.trim(), password);
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
      {(idleNotice || params.get("expired")) && !error && (
        <Alert tone="amber">{idleNotice ? "You were signed out after 3 minutes without activity. Please log in again." : "Your session expired. Please log in again."}</Alert>
      )}
      {error && <Alert>{error}</Alert>}
      <Field label="Email" required>
        {(id) => (
          <Input id={id} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        )}
      </Field>
      <Field label="Password" required>
        {(id) => (
          <Input
            id={id}
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        )}
      </Field>
      <Button type="submit" loading={submitting} className="w-full">
        Log in
      </Button>
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
          <p className="mt-1 mb-6 text-sm text-ink-soft">Use the staff account your admin created for you.</p>
          <Suspense fallback={<Loading />}>
            <LoginForm />
          </Suspense>
        </div>
      </div>
    </main>
  );
}

