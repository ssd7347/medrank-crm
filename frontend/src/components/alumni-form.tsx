"use client";

import { useState } from "react";

import { ApiError, api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import { COURSES, QUOTAS, type College } from "@/lib/types";
import type { AlumniDetail } from "@/lib/types-growth";

import { CollegePicker } from "./college-picker";
import { Alert, Button, Checkbox, Field, Input, Select, Textarea } from "./ui";

/** Adds a student to the alumni directory (pass `studentId`) or edits an existing record (pass `existing`). */
export function AlumniForm({ studentId, existing, onDone }: { studentId?: number; existing?: AlumniDetail; onDone: (d: AlumniDetail) => void }) {
  const a = existing?.alumni;
  const [college, setCollege] = useState<College | null>(null);
  // An existing record may name a college that is not in our database; keep the text until one is picked.
  const [collegeName, setCollegeName] = useState(a?.collegeName ?? "");
  const [pickFromList, setPickFromList] = useState(!a);
  const [v, setV] = useState({
    course: a?.course ?? "MBBS",
    quota: a?.quota ?? "",
    admissionYear: String(a?.admissionYear ?? new Date().getFullYear()),
    willingToRefer: a?.willingToRefer ?? false,
    notes: existing?.notes ?? "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      const body = {
        collegeId: pickFromList ? (college?.id ?? null) : (a?.collegeName === collegeName.trim() ? a.collegeId : null),
        collegeName: pickFromList ? null : collegeName.trim(),
        course: v.course,
        quota: v.quota || null,
        admissionYear: Number(v.admissionYear),
        willingToRefer: v.willingToRefer,
        notes: v.notes,
      };
      onDone(
        existing
          ? await api<AlumniDetail>(`/api/alumni/${existing.alumni.id}`, { method: "PUT", body })
          : await api<AlumniDetail>(`/api/students/${studentId}/alumni`, { body }),
      );
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="College admitted to" required>
        {(id) =>
          pickFromList ? (
            <div className="space-y-1.5">
              <CollegePicker value={college} onChange={setCollege} />
              <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => setPickFromList(false)}>
                Not in the list? Type the name instead
              </button>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Input id={id} required maxLength={200} value={collegeName} onChange={(e) => setCollegeName(e.target.value)} />
              <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => setPickFromList(true)}>
                Pick from our college list
              </button>
            </div>
          )
        }
      </Field>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Course" required>
          {(id) => <Select id={id} value={v.course} onChange={(e) => setV({ ...v, course: e.target.value as typeof v.course })} options={COURSES} />}
        </Field>
        <Field label="Quota">{(id) => <Select id={id} value={v.quota} onChange={(e) => setV({ ...v, quota: e.target.value })} options={QUOTAS} labelFor={label} placeholder="—" />}</Field>
        <Field label="Admission year" required error={fieldErrors.admissionYear}>
          {(id) => <Input id={id} type="number" required min={2013} max={2100} value={v.admissionYear} onChange={(e) => setV({ ...v, admissionYear: e.target.value })} />}
        </Field>
      </div>
      <Checkbox label="Happy to refer friends and juniors" checked={v.willingToRefer} onChange={(e) => setV({ ...v, willingToRefer: e.target.checked })} />
      <Field label="Notes">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={v.notes} onChange={(e) => setV({ ...v, notes: e.target.value })} />}</Field>
      <Button type="submit" loading={saving} disabled={pickFromList && !college}>
        {existing ? "Save" : "Add to alumni directory"}
      </Button>
    </form>
  );
}
