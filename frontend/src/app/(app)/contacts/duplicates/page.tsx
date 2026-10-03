"use client";

import Link from "next/link";
import { useState } from "react";

import { ContactStatusBadge } from "@/components/badges";
import { TagChips } from "@/components/tags";
import { Alert, Badge, Button, Card, EmptyState, Loading, Modal, PageHeader } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatDate } from "@/lib/format";
import type { DuplicateContact, DuplicateGroup } from "@/lib/types";
import { useApi } from "@/lib/use-api";

/**
 * People who are probably in the CRM twice (same email, one's number is the other's alternate number, or the
 * same full name). Keep one and merge the others into it, or say they are different people.
 */
export default function DuplicatesPage() {
  const { hasRole } = useAuth();
  const { plural } = useBranding();
  const { data, error, loading, reload } = useApi<DuplicateGroup[]>(hasRole(...MANAGER_ROLES) ? "/api/contacts/duplicates" : null);
  const [done, setDone] = useState<string | null>(null);

  if (!hasRole(...MANAGER_ROLES)) return <Alert>Only an admin or a manager can merge {plural.toLowerCase()}.</Alert>;

  return (
    <>
      <PageHeader
        title="Possible duplicates"
        subtitle={`${plural} who may be in the CRM more than once. Keep one and merge the rest into it: their history, calls and follow-ups move across.`}
      />
      {error && <Alert>{error}</Alert>}
      {done && (
        <div className="mb-4">
          <Alert tone="green">{done}</Alert>
        </div>
      )}
      {loading && !data ? (
        <Loading />
      ) : !data?.length ? (
        <Card>
          <EmptyState title="No duplicates found">Nobody shares an email, a phone number or a full name.</EmptyState>
        </Card>
      ) : (
        <div className="space-y-4">
          {data.map((g) => (
            <GroupCard key={g.contacts.map((c) => c.id).join("-")} group={g} onDone={(msg) => (setDone(msg), reload())} />
          ))}
        </div>
      )}
    </>
  );
}

function GroupCard({ group, onDone }: { group: DuplicateGroup; onDone: (message: string) => void }) {
  const [keepId, setKeepId] = useState(group.contacts[0].id);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const keep = group.contacts.find((c) => c.id === keepId)!;
  const others = group.contacts.filter((c) => c.id !== keepId);

  async function merge() {
    setBusy(true);
    setError(null);
    try {
      for (const o of others) {
        await api(`/api/contacts/${keepId}/merge`, { body: { otherId: o.id } });
      }
      setConfirm(false);
      onDone(`Merged into ${keep.fullName}.`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function dismiss() {
    setBusy(true);
    setError(null);
    try {
      await api("/api/contacts/duplicates/dismiss", { body: { ids: group.contacts.map((c) => c.id) } });
      onDone("Marked as different people. They will not be suggested again.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {group.reasons.map((r) => (
          <Badge key={r} tone="amber">
            {r}
          </Badge>
        ))}
      </div>
      {error && (
        <div className="mb-3">
          <Alert>{error}</Alert>
        </div>
      )}
      <fieldset>
        <legend className="mb-2 text-xs font-medium text-ink-soft">Which one to keep?</legend>
        <div className="grid gap-2 md:grid-cols-2">
          {group.contacts.map((c) => (
            <label
              key={c.id}
              className={`flex cursor-pointer gap-3 rounded-lg border p-3 ${c.id === keepId ? "border-brand-500 bg-brand-50/60" : "border-line hover:bg-muted"}`}
            >
              <input type="radio" name={`keep-${group.contacts[0].id}`} checked={c.id === keepId} onChange={() => setKeepId(c.id)} className="mt-1 accent-brand-600" />
              <Person c={c} />
            </label>
          ))}
        </div>
      </fieldset>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button variant="secondary" disabled={busy} onClick={dismiss}>
          Not the same person
        </Button>
        <Button disabled={busy} onClick={() => setConfirm(true)}>
          Merge into {keep.fullName}
        </Button>
      </div>
      {confirm && (
        <Modal open onClose={() => setConfirm(false)} title={`Merge into ${keep.fullName}?`}>
          <div className="space-y-3 text-sm text-ink-soft">
            <p>
              {others.map((o) => o.fullName).join(", ")} will be removed, and everything about {others.length === 1 ? "them" : "each of them"} moves to{" "}
              {keep.fullName}: timeline, follow-ups, calls, AI calls and consent. Blank details are filled in from the removed record, and its phone number is kept
              as the alternate number.
            </p>
            {others.some((o) => o.status === "DO_NOT_CALL") && keep.status !== "DO_NOT_CALL" && (
              <Alert tone="amber">One of them is marked &ldquo;Do not call&rdquo;, so the kept record will be too.</Alert>
            )}
            <p>This cannot be undone.</p>
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setConfirm(false)}>
                Cancel
              </Button>
              <Button loading={busy} onClick={merge}>
                Merge
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Card>
  );
}

function Person({ c }: { c: DuplicateContact }) {
  return (
    <div className="min-w-0 space-y-0.5 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Link href={`/contacts/${c.id}`} className="font-medium text-brand-800 hover:underline" onClick={(e) => e.stopPropagation()}>
          {c.fullName}
        </Link>
        <ContactStatusBadge status={c.status} />
      </div>
      <p className="text-ink-soft">
        {c.phone}
        {c.altPhone && ` · alt ${c.altPhone}`}
      </p>
      {c.email && <p className="truncate text-ink-soft">{c.email}</p>}
      <p className="text-xs text-ink-faint">Added {formatDate(c.createdAt)}</p>
      <TagChips tags={c.tags} />
    </div>
  );
}
