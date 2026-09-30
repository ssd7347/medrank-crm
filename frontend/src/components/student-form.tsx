"use client";

import { useState } from "react";

import { ApiError, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { INDIAN_STATES, label } from "@/lib/format";
import {
  CATEGORIES,
  DOMICILE,
  GENDERS,
  LANGUAGES,
  NATIONALITIES,
  type StudentRequest,
  type UserRef,
} from "@/lib/types";
import type { Branch } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

import { Alert, Button, Checkbox, Field, Input, Select } from "./ui";

export function emptyStudent(): StudentRequest {
  return {
    fullName: "",
    dateOfBirth: null,
    gender: null,
    phone: "",
    email: null,
    parentName: null,
    parentPhone: null,
    category: "GEN",
    pwd: false,
    homeState: "",
    domicileStatus: "UNKNOWN",
    nationality: "INDIAN",
    nriSponsored: false,
    neetYear: new Date().getFullYear(),
    neetRollNo: null,
    neetQualified: true,
    neetScore: null,
    neetPercentile: null,
    neetAir: null,
    categoryRank: null,
    categoryCertValidUntil: null,
    languagePreference: "ENGLISH",
    apaarId: null,
    assignedCounsellorId: null,
    branchId: null,
  };
}

export function StudentForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initial: StudentRequest;
  submitLabel: string;
  onSubmit: (req: StudentRequest) => Promise<void>;
  onCancel?: () => void;
}) {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const [s, setS] = useState<StudentRequest>(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const staff = useApi<UserRef[]>(isAdmin ? "/api/users/assignable" : null);
  const branches = useApi<Branch[]>(isAdmin ? "/api/branches" : null);

  function text<K extends keyof StudentRequest>(k: K) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setS((p) => ({ ...p, [k]: e.target.value === "" ? null : e.target.value }));
  }
  function number<K extends keyof StudentRequest>(k: K) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setS((p) => ({ ...p, [k]: e.target.value === "" ? null : Number(e.target.value) }));
  }
  function bool<K extends keyof StudentRequest>(k: K) {
    return (e: React.ChangeEvent<HTMLInputElement>) => setS((p) => ({ ...p, [k]: e.target.checked }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      await onSubmit(s);
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  const reserved = s.category !== "GEN";

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert>{error}</Alert>}

      <fieldset className="grid gap-4 sm:grid-cols-3">
        <legend className="mb-2 text-sm font-semibold">Student & parent</legend>
        <Field label="Full name" required error={fieldErrors.fullName}>
          {(id) => <Input id={id} required maxLength={120} value={s.fullName} onChange={(e) => setS({ ...s, fullName: e.target.value })} />}
        </Field>
        <Field label="Date of birth" error={fieldErrors.dateOfBirth}>
          {(id) => <Input id={id} type="date" value={s.dateOfBirth ?? ""} onChange={text("dateOfBirth")} />}
        </Field>
        <Field label="Gender">
          {(id) => <Select id={id} value={s.gender ?? ""} onChange={text("gender")} options={GENDERS} labelFor={label} placeholder="—" />}
        </Field>
        <Field label="Phone" required error={fieldErrors.phone}>
          {(id) => <Input id={id} required type="tel" value={s.phone} onChange={(e) => setS({ ...s, phone: e.target.value })} />}
        </Field>
        <Field label="Email" error={fieldErrors.email}>
          {(id) => <Input id={id} type="email" value={s.email ?? ""} onChange={text("email")} />}
        </Field>
        <Field label="Preferred language">
          {(id) => (
            <Select id={id} value={s.languagePreference} onChange={(e) => setS({ ...s, languagePreference: e.target.value as StudentRequest["languagePreference"] })} options={LANGUAGES} labelFor={label} />
          )}
        </Field>
        <Field label="Parent / guardian name">
          {(id) => <Input id={id} maxLength={120} value={s.parentName ?? ""} onChange={text("parentName")} />}
        </Field>
        <Field label="Parent phone" error={fieldErrors.parentPhone}>
          {(id) => <Input id={id} type="tel" value={s.parentPhone ?? ""} onChange={text("parentPhone")} />}
        </Field>
        <Field label="APAAR ID" hint="Optional national student ID">
          {(id) => <Input id={id} maxLength={20} value={s.apaarId ?? ""} onChange={text("apaarId")} />}
        </Field>
      </fieldset>

      <fieldset className="grid gap-4 sm:grid-cols-3">
        <legend className="mb-2 text-sm font-semibold">Eligibility inputs</legend>
        <Field label="Category" required>
          {(id) => <Select id={id} value={s.category} onChange={(e) => setS({ ...s, category: e.target.value as StudentRequest["category"] })} options={CATEGORIES} />}
        </Field>
        <Field label="Category certificate valid until" hint={reserved ? "Needed to keep the reservation benefit" : "Only for reserved categories"}>
          {(id) => <Input id={id} type="date" disabled={!reserved} value={s.categoryCertValidUntil ?? ""} onChange={text("categoryCertValidUntil")} />}
        </Field>
        <div className="flex items-end pb-2">
          <Checkbox label="Person with disability (PwD)" checked={s.pwd} onChange={bool("pwd")} />
        </div>
        <Field label="Home state" required error={fieldErrors.homeState}>
          {(id) => <Select id={id} required value={s.homeState} onChange={(e) => setS({ ...s, homeState: e.target.value })} options={INDIAN_STATES} placeholder="Choose…" />}
        </Field>
        <Field label="Domicile" required>
          {(id) => (
            <Select id={id} value={s.domicileStatus} onChange={(e) => setS({ ...s, domicileStatus: e.target.value as StudentRequest["domicileStatus"] })} options={DOMICILE} labelFor={label} />
          )}
        </Field>
        <Field label="Nationality" required>
          {(id) => (
            <Select id={id} value={s.nationality} onChange={(e) => setS({ ...s, nationality: e.target.value as StudentRequest["nationality"] })} options={NATIONALITIES} labelFor={label} />
          )}
        </Field>
        <div className="sm:col-span-3">
          <Checkbox label="Sponsored by an NRI relative (for NRI quota)" checked={s.nriSponsored} onChange={bool("nriSponsored")} />
        </div>
      </fieldset>

      <fieldset className="grid gap-4 sm:grid-cols-4">
        <legend className="mb-2 text-sm font-semibold">NEET result</legend>
        <Field label="NEET year" error={fieldErrors.neetYear}>
          {(id) => <Input id={id} type="number" min={2013} max={2100} value={s.neetYear ?? ""} onChange={number("neetYear")} />}
        </Field>
        <Field label="Roll number" error={fieldErrors.neetRollNo}>
          {(id) => <Input id={id} maxLength={20} value={s.neetRollNo ?? ""} onChange={text("neetRollNo")} />}
        </Field>
        <Field label="Score (out of 720)" error={fieldErrors.neetScore}>
          {(id) => <Input id={id} type="number" min={-180} max={720} value={s.neetScore ?? ""} onChange={number("neetScore")} />}
        </Field>
        <Field label="Percentile" error={fieldErrors.neetPercentile}>
          {(id) => <Input id={id} type="number" step="0.0000001" min={0} max={100} value={s.neetPercentile ?? ""} onChange={number("neetPercentile")} />}
        </Field>
        <Field label="All India Rank" error={fieldErrors.neetAir}>
          {(id) => <Input id={id} type="number" min={1} value={s.neetAir ?? ""} onChange={number("neetAir")} />}
        </Field>
        <Field label="Category rank" error={fieldErrors.categoryRank}>
          {(id) => <Input id={id} type="number" min={1} value={s.categoryRank ?? ""} onChange={number("categoryRank")} />}
        </Field>
        <div className="flex items-end pb-2 sm:col-span-2">
          <Checkbox label="Qualified NEET" checked={s.neetQualified} onChange={bool("neetQualified")} />
        </div>
      </fieldset>

      {isAdmin && (
        <fieldset className="grid gap-4 sm:grid-cols-3">
          <legend className="mb-2 text-sm font-semibold">Ownership</legend>
          <Field label="Counsellor">
            {(id) => (
              <Select
                id={id}
                value={s.assignedCounsellorId?.toString() ?? ""}
                onChange={number("assignedCounsellorId")}
                placeholder="Not assigned"
                options={(staff.data ?? []).filter((u) => u.role !== "TELECALLER").map((u) => ({ value: String(u.id), label: u.fullName }))}
              />
            )}
          </Field>
          {!!branches.data?.length && (
            <Field label="Branch">
              {(id) => (
                <Select
                  id={id}
                  value={s.branchId?.toString() ?? ""}
                  onChange={number("branchId")}
                  placeholder="Head office (no branch)"
                  options={branches.data!.filter((b) => b.active || b.id === s.branchId).map((b) => ({ value: String(b.id), label: b.name }))}
                />
              )}
            </Field>
          )}
        </fieldset>
      )}

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
