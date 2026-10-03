"use client";

import { useState } from "react";

import { ApiError, api, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { label } from "@/lib/format";
import { useStages } from "@/lib/stages";
import { type Branch, type Contact, type ContactRequest, type FieldDef, type UserRef } from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Alert, Button, Field, Input, Select, Textarea } from "./ui";

/** Shows the right kind of box for a custom field: dates get a date picker, amounts and numbers a number box. */
export function FieldInput({ field, value, onChange, id }: { field: FieldDef; value: string; onChange: (v: string) => void; id: string }) {
  if (field.fieldType === "DATE") return <Input id={id} type="date" value={value} onChange={(e) => onChange(e.target.value)} />;
  if (field.fieldType === "NUMBER" || field.fieldType === "AMOUNT")
    return <Input id={id} type="number" inputMode="decimal" step="any" value={value} onChange={(e) => onChange(e.target.value)} />;
  return <Input id={id} maxLength={500} value={value} onChange={(e) => onChange(e.target.value)} />;
}

/** Create or edit a contact, custom fields included. */
export function ContactForm({ existing, onSaved, onCancel }: { existing?: Contact; onSaved: (c: Contact) => void; onCancel?: () => void }) {
  const { hasRole, user } = useAuth();
  const { contactLabel } = useBranding();
  const { stages } = useStages();
  const manager = hasRole(...MANAGER_ROLES);
  const fields = useApi<FieldDef[]>("/api/fields");
  const staff = useApi<UserRef[]>(manager ? "/api/users/assignable" : null);
  const branches = useApi<Branch[]>(manager && !user?.branch ? "/api/branches" : null);

  const [v, setV] = useState<ContactRequest>({
    fullName: existing?.fullName ?? "",
    phone: existing?.phone ?? "",
    altPhone: existing?.altPhone ?? "",
    email: existing?.email ?? "",
    status: existing?.status ?? "NEW",
    source: existing?.source ?? "",
    notes: existing?.notes ?? "",
    assignedToId: existing?.assignedTo?.id ?? null,
    branchId: existing?.branch?.id ?? null,
    fields: { ...(existing?.fields ?? {}) },
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const set = (k: keyof ContactRequest) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setV((old) => ({ ...old, [k]: e.target.value }));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setErrors({});
    const body = {
      ...v,
      altPhone: v.altPhone || null,
      email: v.email || null,
      source: v.source || null,
      notes: v.notes || null,
      // Every known field is sent, so clearing a box removes the value.
      fields: Object.fromEntries((fields.data ?? []).map((f) => [f.key, v.fields[f.key] ?? ""])),
    };
    try {
      onSaved(existing ? await api<Contact>(`/api/contacts/${existing.id}`, { method: "PUT", body }) : await api<Contact>("/api/contacts", { body }));
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setErrors(err.body.errors);
      setError(err instanceof ApiError && err.status === 409 ? `${err.message}.` : errorMessage(err));
      setSaving(false);
    }
  }

  const activeFields = (fields.data ?? []).filter((f) => f.active || v.fields[f.key]);

  return (
    <form onSubmit={save} className="space-y-5">
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Full name" required error={errors.fullName}>
          {(id) => <Input id={id} required maxLength={120} value={v.fullName} onChange={set("fullName")} />}
        </Field>
        <Field label="Phone" required error={errors.phone} hint="10-digit mobile, or with country code">
          {(id) => <Input id={id} type="tel" required maxLength={20} value={v.phone} onChange={set("phone")} />}
        </Field>
        <Field label="Alternate phone" error={errors.altPhone}>
          {(id) => <Input id={id} type="tel" maxLength={20} value={v.altPhone ?? ""} onChange={set("altPhone")} />}
        </Field>
        <Field label="Email" error={errors.email}>
          {(id) => <Input id={id} type="email" maxLength={160} value={v.email ?? ""} onChange={set("email")} />}
        </Field>
        <Field label="Status">
          {(id) => <Select id={id} value={v.status ?? "NEW"} options={stages.map((s) => ({ value: s.key, label: s.label }))} onChange={set("status")} />}
        </Field>
        <Field label="Source" hint="Where this person came from">
          {(id) => <Input id={id} maxLength={60} value={v.source ?? ""} onChange={set("source")} placeholder="e.g. Walk-in, Website, Referral" />}
        </Field>
        {manager && (
          <Field label="Assigned to">
            {(id) => (
              <Select
                id={id}
                value={v.assignedToId ? String(v.assignedToId) : ""}
                placeholder="Nobody yet"
                options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fullName} (${label(u.role)})` }))}
                onChange={(e) => setV((o) => ({ ...o, assignedToId: e.target.value ? Number(e.target.value) : null }))}
              />
            )}
          </Field>
        )}
        {manager && !user?.branch && (branches.data?.length ?? 0) > 0 && (
          <Field label="Branch">
            {(id) => (
              <Select
                id={id}
                value={v.branchId ? String(v.branchId) : ""}
                placeholder="No branch"
                options={(branches.data ?? []).filter((b) => b.active).map((b) => ({ value: String(b.id), label: b.name }))}
                onChange={(e) => setV((o) => ({ ...o, branchId: e.target.value ? Number(e.target.value) : null }))}
              />
            )}
          </Field>
        )}
      </div>

      {activeFields.length > 0 && (
        <div>
          <p className="mb-2 text-xs font-semibold tracking-wide text-ink-faint uppercase">More about this {contactLabel.toLowerCase()}</p>
          <div className="grid gap-4 sm:grid-cols-2">
            {activeFields.map((f) => (
              <Field key={f.key} label={f.label}>
                {(id) => <FieldInput id={id} field={f} value={v.fields[f.key] ?? ""} onChange={(val) => setV((o) => ({ ...o, fields: { ...o.fields, [f.key]: val } }))} />}
              </Field>
            ))}
          </div>
        </div>
      )}

      <Field label="Notes">{(id) => <Textarea id={id} maxLength={2000} value={v.notes ?? ""} onChange={set("notes")} />}</Field>

      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" loading={saving}>
          {existing ? "Save" : `Add ${contactLabel.toLowerCase()}`}
        </Button>
      </div>
    </form>
  );
}
