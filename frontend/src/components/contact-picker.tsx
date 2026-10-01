"use client";

import { useEffect, useState } from "react";

import { useBranding } from "@/lib/branding";
import type { ContactListItem, Page } from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Input, Spinner } from "./ui";

export type PickedContact = { id: number; fullName: string };

/** Search-as-you-type chooser (name, phone or any field). */
export function ContactPicker({ value, onChange }: { value: PickedContact | null; onChange: (c: PickedContact | null) => void }) {
  const { contactLabel } = useBranding();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(t);
  }, [text]);

  const { data, loading } = useApi<Page<ContactListItem>>(open && q.length >= 2 ? "/api/contacts" : null, { q, size: 8 });

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
        aria-label={`Search ${contactLabel.toLowerCase()}`}
        placeholder="Search name or phone"
        value={text}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
      />
      {open && q.length >= 2 && (
        <div className="absolute inset-x-0 z-30 mt-1 max-h-64 overflow-y-auto rounded-lg border border-line bg-surface shadow-lg">
          {loading && !data ? (
            <div className="flex justify-center p-3 text-ink-faint">
              <Spinner className="h-4 w-4" />
            </div>
          ) : !data?.items.length ? (
            <p className="p-3 text-sm text-ink-faint">No match</p>
          ) : (
            data.items.map((c) => (
              <button
                key={c.id}
                type="button"
                onClick={() => {
                  onChange({ id: c.id, fullName: c.fullName });
                  setOpen(false);
                  setText("");
                }}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-muted"
              >
                <span className="font-medium">{c.fullName}</span> <span className="text-ink-faint">· {c.phone}</span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
