"use client";

import { useEffect, useState } from "react";

import { Alert, Button, Card, Field, Input, PageHeader } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { pluralOf, useBranding } from "@/lib/branding";
import type { Branding } from "@/lib/types";

/** The white-label settings: each customer company sees its own name, colour and AI agent name. */
export default function SettingsPage() {
  const branding = useBranding();
  const [v, setV] = useState<Branding>({
    companyName: branding.companyName,
    agentName: branding.agentName,
    brandColor: branding.brandColor,
    supportPhone: branding.supportPhone,
    contactLabel: branding.contactLabel,
  });
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The branding loads after the first render; fill the form once it arrives.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setV({
      companyName: branding.companyName,
      agentName: branding.agentName,
      brandColor: branding.brandColor,
      supportPhone: branding.supportPhone,
      contactLabel: branding.contactLabel,
    });
  }, [branding.companyName, branding.agentName, branding.brandColor, branding.supportPhone, branding.contactLabel]);

  const set = (k: keyof Branding) => (e: React.ChangeEvent<HTMLInputElement>) => setV((o) => ({ ...o, [k]: e.target.value }));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      await api("/api/settings/branding", { method: "PUT", body: { ...v, supportPhone: v.supportPhone || null } });
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
          <div className="flex justify-end">
            <Button type="submit" loading={saving}>
              Save
            </Button>
          </div>
        </form>
      </Card>
    </>
  );
}
