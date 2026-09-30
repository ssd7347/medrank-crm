"use client";

import { useEffect, useState } from "react";

import type { Page, StudentListItem } from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Input, Spinner } from "./ui";

export type PickedStudent = { id: number; fullName: string };

/** Search-as-you-type student chooser (name, phone or NEET roll number). */
export function StudentPicker({ value, onChange }: { value: PickedStudent | null; onChange: (s: PickedStudent | null) => void }) {
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(t);
  }, [text]);

  const { data, loading, error } = useApi<Page<StudentListItem>>(open && q.length >= 2 ? "/api/students" : null, { q, size: 8 });

  if (value) {
    return (
      <div className="flex items-center justify-between gap-2 rounded-lg border border-line bg-muted px-3 py-2 text-sm">
        <span className="min-w-0 truncate font-medium">{value.fullName}</span>
        <button type="button" onClick={() => onChange(null)} className="shrink-0 text-xs text-brand-700 hover:underline">
          Change
        </button>
      </div>
    );
  }

  return (
    <div className="relative">
      <Input
        value={text}
        placeholder="Search student name or phone"
        aria-label="Search students"
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
      />
      {open && q.length >= 2 && (
        <div className="absolute z-30 mt-1 max-h-64 w-full overflow-y-auto rounded-lg border border-line bg-surface shadow-lg">
          {error ? (
            <p className="p-3 text-sm text-red-700">{error}</p>
          ) : loading && !data ? (
            <div className="flex justify-center p-3 text-ink-faint">
              <Spinner className="h-4 w-4" />
            </div>
          ) : !data?.items.length ? (
            <p className="p-3 text-sm text-ink-faint">No students match.</p>
          ) : (
            <ul>
              {data.items.map((s) => (
                <li key={s.id}>
                  <button
                    type="button"
                    onClick={() => {
                      onChange({ id: s.id, fullName: s.fullName });
                      setOpen(false);
                      setText("");
                    }}
                    className="block w-full px-3 py-2 text-left text-sm hover:bg-muted"
                  >
                    <span className="font-medium">{s.fullName}</span>
                    <span className="block text-xs text-ink-soft tabular-nums">{s.phone}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
