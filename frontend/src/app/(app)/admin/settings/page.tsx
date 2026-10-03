"use client";

import { useEffect, useRef, useState } from "react";

import { Logo } from "@/components/logo";
import { Alert, Button, Card, Field, Input, PageHeader, Textarea } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { pluralOf, useBranding } from "@/lib/branding";
import type { Branding } from "@/lib/types";

type Form = Pick<Branding, "companyName" | "agentName" | "brandColor" | "supportPhone" | "contactLabel" | "loginHeadline" | "loginText">;

/** The white-label settings: each customer company sees its own name, logo, colour, wording and AI agent name. */
export default function SettingsPage() {
  const branding = useBranding();
  const pick = (b: Branding): Form => ({
    companyName: b.companyName,
    agentName: b.agentName,
    brandColor: b.brandColor,
    supportPhone: b.supportPhone,
    contactLabel: b.contactLabel,
    loginHeadline: b.loginHeadline,
    loginText: b.loginText,
  });
  const [v, setV] = useState<Form>(pick(branding));
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The branding loads after the first render; fill the form once it arrives.
  const loadedKey = JSON.stringify(pick(branding));
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setV(JSON.parse(loadedKey) as Form);
  }, [loadedKey]);

  const set = (k: keyof Form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setV((o) => ({ ...o, [k]: e.target.value }));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      await api("/api/settings/branding", {
        method: "PUT",
        body: { ...v, supportPhone: v.supportPhone || null, loginHeadline: v.loginHeadline || null, loginText: v.loginText || null },
      });
      branding.reload();
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHeader title="Company settings" subtitle="How the CRM and the AI agent present this company. Changes apply at once, including on the login page." />
      <div className="space-y-4">
        <LogoCard />
        <Card>
          <form onSubmit={save} className="space-y-4">
            {error && <Alert>{error}</Alert>}
            {saved && <Alert tone="green">Saved.</Alert>}
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Company name" required hint="Shown in the menu and said by the AI agent on every call.">
                {(id) => <Input id={id} required maxLength={120} value={v.companyName} onChange={set("companyName")} />}
              </Field>
              <Field label="AI agent's name" required hint="How the agent introduces itself, e.g. Asha.">
                {(id) => <Input id={id} required maxLength={40} value={v.agentName} onChange={set("agentName")} />}
              </Field>
              <Field label="What contacts are called" required hint={`For example Customer, Patient, Lead. Shown as "${pluralOf(v.contactLabel || "Contact")}" in the menu.`}>
                {(id) => <Input id={id} required maxLength={30} value={v.contactLabel} onChange={set("contactLabel")} />}
              </Field>
              <Field label="Support phone" hint="Optional. For your own reference and future use in scripts.">
                {(id) => <Input id={id} type="tel" maxLength={20} value={v.supportPhone ?? ""} onChange={set("supportPhone")} />}
              </Field>
              <Field label="Brand colour">
                {(id) => (
                  <div className="flex items-center gap-3">
                    <input id={id} type="color" value={v.brandColor} onChange={set("brandColor")} className="h-10 w-14 cursor-pointer rounded-lg border border-line bg-surface p-1" />
                    <Input aria-label="Colour code" maxLength={7} value={v.brandColor} onChange={set("brandColor")} className="font-mono" />
                  </div>
                )}
              </Field>
            </div>
            <fieldset className="space-y-4 border-t border-line pt-4">
              <legend className="text-sm font-semibold text-ink">Login page</legend>
              <Field label="Headline" hint="Optional. Leave empty for the standard wording.">
                {(id) => <Input id={id} maxLength={120} value={v.loginHeadline ?? ""} onChange={set("loginHeadline")} placeholder="Every customer reached, every call followed up." />}
              </Field>
              <Field label="Text under the headline" hint="Optional, up to 300 characters.">
                {(id) => <Textarea id={id} rows={2} maxLength={300} value={v.loginText ?? ""} onChange={set("loginText")} />}
              </Field>
            </fieldset>
            <div className="flex justify-end">
              <Button type="submit" loading={saving}>
                Save
              </Button>
            </div>
          </form>
        </Card>
      </div>
    </>
  );
}

function LogoCard() {
  const branding = useBranding();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function upload(file: File) {
    setError(null);
    if (file.size > 300 * 1024) {
      setError("The logo must be 300 KB or smaller.");
      return;
    }
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", file);
      await api("/api/settings/logo", { method: "POST", form });
      branding.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api("/api/settings/logo", { method: "DELETE" });
      branding.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <h2 className="text-sm font-semibold text-ink">Logo</h2>
          <p className="text-sm text-ink-soft">PNG, JPEG or WebP, up to 300 KB. A wide logo on a transparent background looks best.</p>
          <div className="pt-2">
            <Logo />
          </div>
        </div>
        <div className="flex gap-2">
          <input ref={input} type="file" accept="image/png,image/jpeg,image/webp" className="hidden" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
          <Button variant="secondary" loading={busy} onClick={() => input.current?.click()}>
            {branding.logoUrl ? "Change logo" : "Upload logo"}
          </Button>
          {branding.logoUrl && (
            <Button variant="secondary" disabled={busy} onClick={remove}>
              Remove
            </Button>
          )}
        </div>
      </div>
      {error && (
        <div className="mt-3">
          <Alert>{error}</Alert>
        </div>
      )}
    </Card>
  );
}
