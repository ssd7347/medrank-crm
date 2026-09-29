"use client";

import { useState } from "react";

import { ApiError, api, errorMessage } from "@/lib/api";
import { INDIAN_STATES, label } from "@/lib/format";
import {
  CATEGORIES,
  COLLEGE_TYPES,
  COURSES,
  QUOTAS,
  ROUNDS,
  type ChangeAction,
  type ChangeEntity,
  type ChangeRequest,
  type College,
  type CutoffRow,
  type FeeRow,
  type SeatRow,
} from "@/lib/types";

import { Alert, Button, Checkbox, Field, Input, Select, Textarea } from "./ui";

/** Submits a master-data change. Admin changes apply immediately; others wait for approval. */
export function submitChange(entityType: ChangeEntity, action: ChangeAction, entityId: number | null, payload: unknown) {
  return api<ChangeRequest>("/api/change-requests", { body: { entityType, action, entityId, payload } });
}

export function outcomeMessage(r: ChangeRequest) {
  return r.status === "APPROVED" ? "Saved. The change is live." : "Submitted. An admin must approve it before it goes live.";
}

function useSubmit(onDone: (r: ChangeRequest) => void) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  async function run(fn: () => Promise<ChangeRequest>) {
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      onDone(await fn());
    } catch (e) {
      if (e instanceof ApiError && e.body.errors) setFieldErrors(e.body.errors);
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  }
  return { saving, error, fieldErrors, run };
}

export function CollegeForm({ college, onDone }: { college?: College; onDone: (r: ChangeRequest) => void }) {
  const [c, setC] = useState({
    name: college?.name ?? "",
    code: college?.code ?? "",
    collegeType: college?.collegeType ?? "GOVERNMENT",
    state: college?.state ?? "",
    city: college?.city ?? "",
    affiliatedUniversity: college?.affiliatedUniversity ?? "",
    nmcRecognized: college?.nmcRecognized ?? true,
    establishedYear: college?.establishedYear?.toString() ?? "",
    website: college?.website ?? "",
  });
  const { saving, error, fieldErrors, run } = useSubmit(onDone);
  const set = (k: keyof typeof c) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setC({ ...c, [k]: e.target.value });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        run(() =>
          submitChange("COLLEGE", college ? "UPDATE" : "CREATE", college?.id ?? null, {
            ...c,
            code: c.code.trim() || null,
            establishedYear: c.establishedYear ? Number(c.establishedYear) : null,
          }),
        );
      }}
      className="grid gap-4 sm:grid-cols-2"
    >
      {error && (
        <div className="sm:col-span-2">
          <Alert>{error}</Alert>
        </div>
      )}
      <Field label="College name" required className="sm:col-span-2" error={fieldErrors["name"]}>
        {(id) => <Input id={id} required maxLength={200} value={c.name} onChange={set("name")} />}
      </Field>
      <Field label="Code" hint="Short unique code used in CSV uploads, e.g. TN-MMC" error={fieldErrors["code"]}>
        {(id) => <Input id={id} maxLength={30} value={c.code} onChange={set("code")} />}
      </Field>
      <Field label="Type" required>
        {(id) => <Select id={id} value={c.collegeType} onChange={set("collegeType")} options={COLLEGE_TYPES} labelFor={label} />}
      </Field>
      <Field label="State" required error={fieldErrors["state"]}>
        {(id) => <Select id={id} required value={c.state} onChange={set("state")} options={INDIAN_STATES} placeholder="Choose…" />}
      </Field>
      <Field label="City">{(id) => <Input id={id} maxLength={80} value={c.city} onChange={set("city")} />}</Field>
      <Field label="Affiliated university" className="sm:col-span-2">
        {(id) => <Input id={id} maxLength={200} value={c.affiliatedUniversity} onChange={set("affiliatedUniversity")} />}
      </Field>
      <Field label="Established" error={fieldErrors["establishedYear"]}>
        {(id) => <Input id={id} type="number" min={1800} max={2100} value={c.establishedYear} onChange={set("establishedYear")} />}
      </Field>
      <Field label="Website" error={fieldErrors["website"]}>
        {(id) => <Input id={id} type="url" placeholder="https://" value={c.website} onChange={set("website")} />}
      </Field>
      <div className="sm:col-span-2">
        <Checkbox label="NMC recognised" checked={c.nmcRecognized} onChange={(e) => setC({ ...c, nmcRecognized: e.target.checked })} />
      </div>
      <div className="sm:col-span-2">
        <Button type="submit" loading={saving}>
          {college ? "Submit changes" : "Add college"}
        </Button>
      </div>
    </form>
  );
}

type RowKind = "SEAT_MATRIX" | "FEE" | "CUTOFF";
type AnyRow = SeatRow | FeeRow | CutoffRow;

/** Add/edit one seat-matrix, fee or cutoff row for a college. */
export function RowForm({ kind, collegeId, row, onDone }: { kind: RowKind; collegeId: number; row?: AnyRow; onDone: (r: ChangeRequest) => void }) {
  const r = row as Partial<SeatRow & FeeRow & CutoffRow> | undefined;
  const [v, setV] = useState({
    course: r?.course ?? "MBBS",
    quota: r?.quota ?? "AIQ",
    category: r?.category ?? "GEN",
    pwd: r?.pwd ?? false,
    counsellingRound: r?.counsellingRound ?? "ROUND_1",
    academicYear: (r?.academicYear ?? new Date().getFullYear()).toString(),
    seats: r?.seats?.toString() ?? "",
    closingRank: r?.closingRank?.toString() ?? "",
    annualTuition: r?.annualTuition?.toString() ?? "",
    otherFees: r?.otherFees?.toString() ?? "",
    notes: r?.notes ?? "",
  });
  const { saving, error, fieldErrors, run } = useSubmit(onDone);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setV({ ...v, [k]: e.target.value });

  function payload() {
    const base = { collegeId, course: v.course, quota: v.quota, academicYear: Number(v.academicYear) };
    if (kind === "FEE") {
      return { ...base, annualTuition: Number(v.annualTuition), otherFees: v.otherFees ? Number(v.otherFees) : null, notes: v.notes || null };
    }
    const keyed = { ...base, category: v.category, pwd: v.pwd, counsellingRound: v.counsellingRound };
    return kind === "SEAT_MATRIX" ? { ...keyed, seats: Number(v.seats) } : { ...keyed, closingRank: Number(v.closingRank) };
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        run(() => submitChange(kind, row ? "UPDATE" : "CREATE", row?.id ?? null, payload()));
      }}
      className="grid gap-4 sm:grid-cols-3"
    >
      {error && (
        <div className="sm:col-span-3">
          <Alert>{error}</Alert>
        </div>
      )}
      <Field label="Course">{(id) => <Select id={id} value={v.course} onChange={set("course")} options={COURSES} />}</Field>
      <Field label="Quota">{(id) => <Select id={id} value={v.quota} onChange={set("quota")} options={QUOTAS} labelFor={label} />}</Field>
      <Field label="Academic year" error={fieldErrors["academicYear"]}>
        {(id) => <Input id={id} type="number" required min={2013} max={2100} value={v.academicYear} onChange={set("academicYear")} />}
      </Field>
      {kind !== "FEE" && (
        <>
          <Field label="Category">{(id) => <Select id={id} value={v.category} onChange={set("category")} options={CATEGORIES} />}</Field>
          <Field label="Round">{(id) => <Select id={id} value={v.counsellingRound} onChange={set("counsellingRound")} options={ROUNDS} labelFor={label} />}</Field>
          <div className="flex items-end pb-2">
            <Checkbox label="PwD seat" checked={v.pwd} onChange={(e) => setV({ ...v, pwd: e.target.checked })} />
          </div>
        </>
      )}
      {kind === "SEAT_MATRIX" && (
        <Field label="Seats" required error={fieldErrors["seats"]}>
          {(id) => <Input id={id} type="number" required min={0} max={5000} value={v.seats} onChange={set("seats")} />}
        </Field>
      )}
      {kind === "CUTOFF" && (
        <Field label="Closing rank" required error={fieldErrors["closingRank"]}>
          {(id) => <Input id={id} type="number" required min={1} value={v.closingRank} onChange={set("closingRank")} />}
        </Field>
      )}
      {kind === "FEE" && (
        <>
          <Field label="Annual tuition (₹)" required error={fieldErrors["annualTuition"]}>
            {(id) => <Input id={id} type="number" required min={0} value={v.annualTuition} onChange={set("annualTuition")} />}
          </Field>
          <Field label="Other fees per year (₹)" error={fieldErrors["otherFees"]}>
            {(id) => <Input id={id} type="number" min={0} value={v.otherFees} onChange={set("otherFees")} />}
          </Field>
          <Field label="Notes" className="sm:col-span-3">
            {(id) => <Textarea id={id} rows={2} maxLength={500} value={v.notes} onChange={set("notes")} />}
          </Field>
        </>
      )}
      <div className="sm:col-span-3">
        <Button type="submit" loading={saving}>
          {row ? "Submit change" : "Add row"}
        </Button>
      </div>
    </form>
  );
}
