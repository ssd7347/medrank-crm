"use client";

import { useEffect, useState } from "react";

import { label } from "@/lib/format";
import type { College, Page } from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Input, Spinner, cx } from "./ui";

/** Search-as-you-type college chooser. `state` limits results (e.g. to a state authority's colleges). */
export function CollegePicker({
  value,
  onChange,
  state,
  placeholder = "Search college name, code or city",
}: {
  value: College | null;
  onChange: (c: College | null) => void;
  state?: string | null;
  placeholder?: string;
}) {
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(t);
  }, [text]);

  const { data, loading } = useApi<Page<College>>(open ? "/api/colleges" : null, { q, state: state ?? undefined, size: 12 });

  if (value) {
    return (
      <div className="flex items-center justify-between gap-2 rounded-lg border border-line bg-muted px-3 py-2 text-sm">
        <span className="min-w-0">
          <span className="font-medium">{value.name}</span>
          <span className="block text-xs text-ink-soft">
            {value.code ? `${value.code} · ` : ""}
            {value.state} · {label(value.collegeType)}
          </span>
        </span>
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
        placeholder={placeholder}
        aria-label="Search colleges"
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
      />
      {open && (
        <div className="absolute z-30 mt-1 max-h-72 w-full overflow-y-auto rounded-lg border border-line bg-surface shadow-lg">
          {loading && !data ? (
            <div className="flex justify-center p-3 text-ink-faint">
              <Spinner className="h-4 w-4" />
            </div>
          ) : !data?.items.length ? (
            <p className="p-3 text-sm text-ink-faint">No colleges match{state ? ` in ${state}` : ""}.</p>
          ) : (
            <ul>
              {data.items.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    onClick={() => {
                      onChange(c);
                      setOpen(false);
                      setText("");
                    }}
                    className={cx("block w-full px-3 py-2 text-left text-sm hover:bg-muted")}
                  >
                    <span className="font-medium">{c.name}</span>
                    <span className="block text-xs text-ink-soft">
                      {c.code ? `${c.code} · ` : ""}
                      {c.city ? `${c.city}, ` : ""}
                      {c.state}
                    </span>
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
