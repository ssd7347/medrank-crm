"use client";

import Link from "next/link";
import { useState } from "react";

import { ApiError, errorMessage, type ProblemBody } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { INDIAN_STATES, label } from "@/lib/format";
import {
  CATEGORIES,
  DOMICILE,
  LANGUAGES,
  LEAD_SOURCES,
  type Associate,
  type Lead,
  type LeadRequest,
  type UserRef,
} from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Alert, Button, Field, Input, Select, Textarea } from "./ui";

type FormState = Record<keyof Omit<LeadRequest, "allowDuplicatePhone">, string>;

function toState(l?: Lead): FormState {
  return {
    fullName: l?.fullName ?? "",
    phone: l?.phone ?? "",
    altPhone: l?.altPhone ?? "",
    email: l?.email ?? "",
    neetRollNo: l?.neetRollNo ?? "",
    neetScore: l?.neetScore?.toString() ?? "",
    neetAir: l?.neetAir?.toString() ?? "",
    category: l?.category ?? "",
    homeState: l?.homeState ?? "",
    domicileStatus: l?.domicileStatus ?? "",
    source: l?.source ?? "PHONE",
    referralAssociateId: l?.referralAssociate?.id.toString() ?? "",
    languagePreference: l?.languagePreference ?? "ENGLISH",
    notes: l?.notes ?? "",
    assignedCounsellorId: l?.assignedCounsellor?.id.toString() ?? "",
  };
}

const num = (s: string) => (s.trim() === "" ? null : Number(s));
const str = (s: string) => (s.trim() === "" ? null : s.trim());

export function LeadForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initial?: Lead;
  submitLabel: string;
  onSubmit: (req: LeadRequest) => Promise<void>;
  onCancel?: () => void;
}) {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const [f, setF] = useState<FormState>(() => toState(initial));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [dupes, setDupes] = useState<ProblemBody | null>(null);

  const associates = useApi<Associate[]>("/api/referral-associates");
  const staff = useApi<UserRef[]>(isAdmin ? "/api/users/assignable" : null);

  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setF((prev) => ({ ...prev, [k]: e.target.value }));

  async function submit(allowDuplicatePhone: boolean) {
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      await onSubmit({
        fullName: f.fullName.trim(),
        phone: f.phone.trim(),
        altPhone: str(f.altPhone),
        email: str(f.email),
        neetRollNo: str(f.neetRollNo),
        neetScore: num(f.neetScore),
        neetAir: num(f.neetAir),
        category: (str(f.category) as LeadRequest["category"]) ?? null,
        homeState: str(f.homeState),
        domicileStatus: (str(f.domicileStatus) as LeadRequest["domicileStatus"]) ?? null,
        source: f.source as LeadRequest["source"],
        referralAssociateId: num(f.referralAssociateId),
        languagePreference: f.languagePreference as LeadRequest["languagePreference"],
        notes: str(f.notes),
        // Non-admins cannot change assignment; send the current value back unchanged.
        assignedCounsellorId: isAdmin ? num(f.assignedCounsellorId) : (initial?.assignedCounsellor?.id ?? null),
        allowDuplicatePhone,
      });
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.body.duplicates) {
        setDupes(e.body);
      } else {
        if (e instanceof ApiError && e.body.errors) setFieldErrors(e.body.errors);
        setError(errorMessage(e));
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setDupes(null);
        submit(false);
      }}
      className="space-y-6"
    >
      {error && <Alert>{error}</Alert>}
      {dupes && (
        <Alert tone="amber">
          <p className="font-medium">{dupes.detail}</p>
          <ul className="mt-1 list-disc pl-5">
            {dupes.duplicates!.map((d) => (
              <li key={d.id}>
                <Link href={`/leads/${d.id}`} target="_blank" className="underline">
                  {d.fullName}
                </Link>{" "}
                · {d.phone}
                {d.neetRollNo && ` · Roll ${d.neetRollNo}`} · {label(d.status)}
              </li>
            ))}
          </ul>
          {dupes.canOverride ? (
            <div className="mt-2 flex items-center gap-2">
              <span>Different person sharing the number (e.g. a sibling)?</span>
              <Button type="button" size="sm" variant="secondary" loading={saving} onClick={() => submit(true)}>
                Save anyway
              </Button>
            </div>
          ) : (
            <p className="mt-1">A NEET roll number belongs to one candidate, so this cannot be saved as a new lead.</p>
          )}
        </Alert>
      )}

      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold">Contact</legend>
        <Field label="Full name" required error={fieldErrors.fullName}>
          {(id) => <Input id={id} required maxLength={120} value={f.fullName} onChange={set("fullName")} />}
        </Field>
        <Field label="Phone" required error={fieldErrors.phone} hint="10-digit mobile; +91 is optional">
          {(id) => <Input id={id} required type="tel" inputMode="tel" value={f.phone} onChange={set("phone")} />}
        </Field>
        <Field label="Alternate phone (parent)" error={fieldErrors.altPhone}>
          {(id) => <Input id={id} type="tel" inputMode="tel" value={f.altPhone} onChange={set("altPhone")} />}
        </Field>
        <Field label="Email" error={fieldErrors.email}>
          {(id) => <Input id={id} type="email" value={f.email} onChange={set("email")} />}
        </Field>
        <Field label="Preferred language">
          {(id) => <Select id={id} value={f.languagePreference} onChange={set("languagePreference")} options={LANGUAGES} labelFor={label} />}
        </Field>
      </fieldset>

      <fieldset className="grid gap-4 sm:grid-cols-3">
        <legend className="mb-2 text-sm font-semibold">NEET details (if known)</legend>
        <Field label="NEET roll number" error={fieldErrors.neetRollNo}>
          {(id) => <Input id={id} maxLength={20} value={f.neetRollNo} onChange={set("neetRollNo")} />}
        </Field>
        <Field label="NEET score" error={fieldErrors.neetScore} hint="Out of 720">
          {(id) => <Input id={id} type="number" min={-180} max={720} value={f.neetScore} onChange={set("neetScore")} />}
        </Field>
        <Field label="All India Rank" error={fieldErrors.neetAir}>
          {(id) => <Input id={id} type="number" min={1} value={f.neetAir} onChange={set("neetAir")} />}
        </Field>
        <Field label="Category">
          {(id) => <Select id={id} value={f.category} onChange={set("category")} options={CATEGORIES} placeholder="Not known" />}
        </Field>
        <Field label="Home state">
          {(id) => <Select id={id} value={f.homeState} onChange={set("homeState")} options={INDIAN_STATES} placeholder="Not known" />}
        </Field>
        <Field label="Domicile">
          {(id) => <Select id={id} value={f.domicileStatus} onChange={set("domicileStatus")} options={DOMICILE} labelFor={label} placeholder="Not known" />}
        </Field>
      </fieldset>

      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold">Source & ownership</legend>
        <Field label="Lead source" required>
          {(id) => <Select id={id} required value={f.source} onChange={set("source")} options={LEAD_SOURCES} labelFor={label} />}
        </Field>
        {f.source === "REFERRAL_ASSOCIATE" && (
          <Field label="Referral associate" required>
            {(id) => (
              <Select
                id={id}
                required
                value={f.referralAssociateId}
                onChange={set("referralAssociateId")}
                placeholder="Choose…"
                options={(associates.data ?? []).filter((a) => a.active || String(a.id) === f.referralAssociateId).map((a) => ({
                  value: String(a.id),
                  label: `${a.fullName}${a.district ? ` (${a.district})` : ""}`,
                }))}
              />
            )}
          </Field>
        )}
        {isAdmin && (
          <Field label="Assigned to" hint="Leave empty to put it in the shared unassigned queue">
            {(id) => (
              <Select
                id={id}
                value={f.assignedCounsellorId}
                onChange={set("assignedCounsellorId")}
                placeholder="Unassigned"
                options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fullName} · ${label(u.role)}` }))}
              />
            )}
          </Field>
        )}
        <Field label="Notes" className="sm:col-span-2" error={fieldErrors.notes}>
          {(id) => <Textarea id={id} maxLength={2000} value={f.notes} onChange={set("notes")} />}
        </Field>
      </fieldset>

      <div className="flex gap-2">
        <Button type="submit" loading={saving}>
          {submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}
