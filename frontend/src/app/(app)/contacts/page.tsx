"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { ContactStatusBadge } from "@/components/badges";
import { TagChips } from "@/components/tags";
import { Alert, Button, ButtonLink, Card, EmptyState, Input, Loading, PageHeader, Pagination, Select, Table, Td, cx } from "@/components/ui";
import { api, downloadProtected, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatDate, formatNumber, formatRupees, label } from "@/lib/format";
import { useStages } from "@/lib/stages";
import { type BulkAction, type BulkResult, type ContactListItem, type FieldDef, type Page, type TagCount, type UserRef } from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function ContactsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Contacts />
    </Suspense>
  );
}

function Contacts() {
  const params = useSearchParams();
  const { hasRole, user } = useAuth();
  const { plural, contactLabel, has } = useBranding();
  const { stages } = useStages();
  const manager = hasRole(...MANAGER_ROLES);
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [tag, setTag] = useState(params.get("tag") ?? "");
  const [owner, setOwner] = useState(""); // "", "mine", "unassigned"
  const importBatchId = params.get("importBatchId");
  const [page, setPage] = useState(0);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  // Ticked rows, or "everyone matching the filter" (allMatching).
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [allMatching, setAllMatching] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => {
      setQ(text.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [text]);

  const query = {
    q,
    status,
    tag,
    assignedToId: owner === "mine" ? user?.id : undefined,
    unassignedOnly: owner === "unassigned" || undefined,
    importBatchId: importBatchId ?? undefined,
  };
  const queryKey = JSON.stringify(query);
  // A different filter means a different list: start the selection again.
  const [lastQuery, setLastQuery] = useState(queryKey);
  if (lastQuery !== queryKey) {
    setLastQuery(queryKey);
    setSelected(new Set());
    setAllMatching(false);
  }

  const { data, error, loading, reload } = useApi<Page<ContactListItem>>("/api/contacts", { ...query, page, size: 25 });
  const fields = useApi<FieldDef[]>("/api/fields");
  const tags = useApi<TagCount[]>("/api/tags");
  // The first few custom fields become columns, so an imported sheet looks familiar.
  const columns = (fields.data ?? []).filter((f) => f.active).slice(0, 3);
  const pageIds = data?.items.map((c) => c.id) ?? [];
  const pageAllTicked = pageIds.length > 0 && pageIds.every((id) => selected.has(id));
  const count = allMatching ? (data?.totalItems ?? 0) : selected.size;

  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const qs = new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined && v !== "").map(([k, v]) => [k, String(v)]));
      await downloadProtected(`/api/contacts/export?${qs}`, `${plural.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.csv`);
    } catch (e) {
      setExportError(errorMessage(e));
    } finally {
      setExporting(false);
    }
  }

  function toggle(id: number) {
    setAllMatching(false);
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function togglePage() {
    setAllMatching(false);
    setSelected((s) => {
      const n = new Set(s);
      pageIds.forEach((id) => (pageAllTicked ? n.delete(id) : n.add(id)));
      return n;
    });
  }

  const chip = (active: boolean, text: string, onClick: () => void) => (
    <button
      onClick={onClick}
      className={cx("shrink-0 rounded-md border px-3 py-1.5 text-xs font-medium transition-colors", active ? "border-brand-600 bg-brand-600 text-on-brand" : "border-line bg-surface hover:bg-muted")}
    >
      {text}
    </button>
  );

  return (
    <>
      <PageHeader
        title={plural}
        subtitle={importBatchId ? "Showing one upload only." : undefined}
        actions={
          <>
            {manager && (
              <>
                <ButtonLink href="/contacts/duplicates" variant="secondary">
                  Find duplicates
                </ButtonLink>
                {has("EXPORT") && (
                  <Button variant="secondary" loading={exporting} onClick={exportCsv}>
                    Export
                  </Button>
                )}
                {has("IMPORT") && (
                  <ButtonLink href="/import" variant="secondary">
                    Import from Excel
                  </ButtonLink>
                )}
              </>
            )}
            <ButtonLink href="/contacts/new">Add {contactLabel.toLowerCase()}</ButtonLink>
          </>
        }
      />
      <div className="mb-3 grid gap-2 sm:grid-cols-[1fr_12rem_12rem]">
        <Input aria-label="Search" placeholder="Search name, phone, email or any field" value={text} onChange={(e) => setText(e.target.value)} />
        <Select
          aria-label="Status"
          value={status}
          placeholder="Any status"
          options={stages.map((s) => ({ value: s.key, label: s.label }))}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        />
        <Select
          aria-label="Tag"
          value={tag}
          placeholder="Any tag"
          options={(tags.data ?? []).map((t) => ({ value: t.tag, label: `${t.tag} (${t.contacts})` }))}
          onChange={(e) => {
            setTag(e.target.value);
            setPage(0);
          }}
        />
      </div>
      <div className="mb-3 flex gap-1.5 overflow-x-auto">
        {chip(owner === "", manager ? "Everyone" : "Mine and unassigned", () => (setOwner(""), setPage(0)))}
        {chip(owner === "mine", "Assigned to me", () => (setOwner("mine"), setPage(0)))}
        {chip(owner === "unassigned", "Unassigned", () => (setOwner("unassigned"), setPage(0)))}
        {importBatchId && (
          <Link href="/contacts" className="shrink-0 rounded-full border border-line px-3 py-1 text-xs hover:bg-muted">
            Show all uploads ✕
          </Link>
        )}
      </div>
      {(error || exportError) && <Alert>{error ?? exportError}</Alert>}
      {manager && count > 0 && data && (
        <BulkBar
          count={count}
          canSelectAll={!allMatching && pageAllTicked && data.totalItems > pageIds.length}
          total={data.totalItems}
          onSelectAll={() => setAllMatching(true)}
          onClear={() => (setSelected(new Set()), setAllMatching(false))}
          request={allMatching ? { filter: { ...query, assignedToId: query.assignedToId ?? null, unassignedOnly: !!query.unassignedOnly, importBatchId: importBatchId ? Number(importBatchId) : null } } : { ids: [...selected] }}
          onDone={() => {
            setSelected(new Set());
            setAllMatching(false);
            reload();
            tags.reload();
          }}
        />
      )}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.items.length ? (
            <EmptyState title={q || status || tag || owner ? "Nothing matches" : `No ${plural.toLowerCase()} yet`}>
              {!q && !status && !tag && !owner && manager && has("IMPORT") && (
                <>
                  Add one, or{" "}
                  <Link href="/import" className="text-brand-700 hover:underline">
                    import your Excel sheet
                  </Link>
                  .
                </>
              )}
            </EmptyState>
          ) : (
            <>
              <Table
                head={[
                  ...(manager ? [<input key="all" type="checkbox" aria-label="Tick everyone on this page" checked={pageAllTicked} onChange={togglePage} className="size-4 accent-brand-600" />] : []),
                  "Name",
                  "Phone",
                  "Status",
                  ...columns.map((c) => c.label),
                  "Assigned to",
                  "Added",
                ]}
              >
                {data.items.map((c) => (
                  <tr key={c.id} className={selected.has(c.id) || allMatching ? "bg-brand-50/60" : undefined}>
                    {manager && (
                      <Td>
                        <input type="checkbox" aria-label={`Tick ${c.fullName}`} checked={allMatching || selected.has(c.id)} onChange={() => toggle(c.id)} className="size-4 accent-brand-600" />
                      </Td>
                    )}
                    <Td>
                      <Link href={`/contacts/${c.id}`} className="font-medium text-brand-800 hover:underline">
                        {c.fullName}
                      </Link>
                      <TagChips tags={c.tags} />
                    </Td>
                    <Td className="whitespace-nowrap">{c.phone}</Td>
                    <Td>
                      <ContactStatusBadge status={c.status} />
                    </Td>
                    {columns.map((f) => (
                      <Td key={f.key}>
                        {c.fields[f.key]
                          ? f.fieldType === "DATE"
                            ? formatDate(c.fields[f.key])
                            : f.fieldType === "AMOUNT"
                              ? formatRupees(Number(c.fields[f.key]))
                              : c.fields[f.key]
                          : "—"}
                      </Td>
                    ))}
                    <Td>{c.assignedTo?.fullName ?? <span className="text-ink-faint">Nobody</span>}</Td>
                    <Td className="whitespace-nowrap">{formatDate(c.createdAt)}</Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
            </>
          )}
        </div>
      </Card>
    </>
  );
}

const ACTIONS: { value: BulkAction; label: string }[] = [
  { value: "ASSIGN", label: "Assign to" },
  { value: "STAGE", label: "Move to stage" },
  { value: "ADD_TAG", label: "Add tag" },
  { value: "REMOVE_TAG", label: "Remove tag" },
];

/** One change for every ticked contact (or everyone matching the filter). */
function BulkBar({
  count,
  canSelectAll,
  total,
  onSelectAll,
  onClear,
  request,
  onDone,
}: {
  count: number;
  canSelectAll: boolean;
  total: number;
  onSelectAll: () => void;
  onClear: () => void;
  request: { ids: number[] } | { filter: Record<string, unknown> };
  onDone: () => void;
}) {
  const { stages } = useStages();
  const staff = useApi<UserRef[]>("/api/users/assignable");
  const tags = useApi<TagCount[]>("/api/tags");
  const [action, setAction] = useState<BulkAction>("ASSIGN");
  const [userId, setUserId] = useState("");
  const [stage, setStage] = useState("");
  const [tag, setTag] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "green" | "red"; text: string } | null>(null);

  const ready = action === "ASSIGN" || (action === "STAGE" ? stage !== "" : tag.trim() !== "");

  async function apply() {
    setBusy(true);
    setMessage(null);
    try {
      const r = await api<BulkResult>("/api/contacts/bulk", {
        body: { ...request, action, userId: action === "ASSIGN" && userId ? Number(userId) : null, stage: action === "STAGE" ? stage : null, tag: action.endsWith("TAG") ? tag.trim() : null },
      });
      setMessage({ tone: "green", text: `Done: ${formatNumber(r.changed)} of ${formatNumber(r.matched)} changed${r.changed < r.matched ? " (the rest already were)" : ""}.` });
      setTag("");
      onDone();
    } catch (e) {
      setMessage({ tone: "red", text: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sticky top-[57px] z-10 mb-3 rounded-lg border border-brand-200 bg-brand-50 p-3 lg:top-2">
      {message && (
        <div className="mb-2">
          <Alert tone={message.tone}>{message.text}</Alert>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-brand-900">{formatNumber(count)} ticked</span>
        {canSelectAll && (
          <button onClick={onSelectAll} className="text-sm text-brand-700 underline">
            Tick all {formatNumber(total)} matching
          </button>
        )}
        <button onClick={onClear} className="text-sm text-ink-soft hover:underline">
          Clear
        </button>
        <div className="flex w-full flex-wrap items-center gap-2 sm:ml-auto sm:w-auto">
          <Select aria-label="Action" value={action} options={ACTIONS} onChange={(e) => setAction(e.target.value as BulkAction)} className="w-auto" />
          {action === "ASSIGN" && (
            <Select
              aria-label="Assign to"
              value={userId}
              placeholder="Nobody (unassign)"
              options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fullName} (${label(u.role)})` }))}
              onChange={(e) => setUserId(e.target.value)}
              className="w-auto"
            />
          )}
          {action === "STAGE" && (
            <Select aria-label="Stage" value={stage} placeholder="Choose a stage" options={stages.map((s) => ({ value: s.key, label: s.label }))} onChange={(e) => setStage(e.target.value)} className="w-auto" />
          )}
          {action === "ADD_TAG" && (
            <>
              <Input aria-label="Tag" list="bulk-tags" maxLength={40} value={tag} onChange={(e) => setTag(e.target.value)} placeholder="Tag" className="w-40" />
              <datalist id="bulk-tags">
                {(tags.data ?? []).map((t) => (
                  <option key={t.tag} value={t.tag} />
                ))}
              </datalist>
            </>
          )}
          {action === "REMOVE_TAG" && (
            <Select aria-label="Tag" value={tag} placeholder="Choose a tag" options={(tags.data ?? []).map((t) => ({ value: t.tag, label: t.tag }))} onChange={(e) => setTag(e.target.value)} className="w-auto" />
          )}
          <Button loading={busy} disabled={!ready} onClick={apply}>
            Apply to {formatNumber(count)}
          </Button>
        </div>
      </div>
    </div>
  );
}
