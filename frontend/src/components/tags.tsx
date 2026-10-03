"use client";

import { useId, useState } from "react";

import { api, errorMessage } from "@/lib/api";
import type { Contact, TagCount } from "@/lib/types";
import { useApi } from "@/lib/use-api";

import { Alert } from "./ui";

export function TagChips({ tags }: { tags: string[] }) {
  if (!tags.length) return null;
  return (
    <span className="mt-1 flex flex-wrap gap-1">
      {tags.map((t) => (
        <span key={t} className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] leading-tight text-ink-soft">
          {t}
        </span>
      ))}
    </span>
  );
}

/** The tags on one contact: remove with ×, add by typing (existing tags are suggested). Saves at once. */
export function TagEditor({ contact, onChanged }: { contact: Contact; onChanged: (c: Contact) => void }) {
  const all = useApi<TagCount[]>("/api/tags");
  const listId = useId();
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(tags: string[]) {
    setBusy(true);
    setError(null);
    try {
      onChanged(await api<Contact>(`/api/contacts/${contact.id}/tags`, { method: "PUT", body: { tags } }));
      all.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function add(e: React.FormEvent) {
    e.preventDefault();
    const t = text.trim();
    if (!t) return;
    setText("");
    if (!contact.tags.some((x) => x.toLowerCase() === t.toLowerCase())) save([...contact.tags, t]);
  }

  return (
    <div className="space-y-2">
      {error && <Alert>{error}</Alert>}
      <div className="flex flex-wrap gap-1.5">
        {contact.tags.length === 0 && <span className="text-sm text-ink-faint">No tags yet.</span>}
        {contact.tags.map((t) => (
          <span key={t} className="inline-flex items-center gap-1 rounded-full bg-muted py-0.5 pr-1 pl-2.5 text-xs text-ink">
            {t}
            <button
              type="button"
              disabled={busy}
              onClick={() => save(contact.tags.filter((x) => x !== t))}
              className="grid size-4 place-items-center rounded-full text-ink-faint hover:bg-line hover:text-ink"
              aria-label={`Remove tag ${t}`}
            >
              ×
            </button>
          </span>
        ))}
      </div>
      <form onSubmit={add} className="flex gap-2">
        <input
          aria-label="Add a tag"
          list={listId}
          maxLength={40}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Add a tag, e.g. Hot lead"
          className="min-w-0 flex-1 rounded-lg border border-line bg-surface px-3 py-1.5 text-sm outline-none focus:border-brand-500"
        />
        <datalist id={listId}>
          {(all.data ?? []).filter((t) => !contact.tags.includes(t.tag)).map((t) => (
            <option key={t.tag} value={t.tag} />
          ))}
        </datalist>
        <button type="submit" disabled={busy || !text.trim()} className="rounded-lg border border-line px-3 py-1.5 text-sm hover:bg-muted disabled:opacity-50">
          Add
        </button>
      </form>
    </div>
  );
}
