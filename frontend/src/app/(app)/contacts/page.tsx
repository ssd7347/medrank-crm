"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { ContactStatusBadge } from "@/components/badges";
import { Alert, Button, ButtonLink, Card, EmptyState, Input, Loading, PageHeader, Pagination, Select, Table, Td, cx } from "@/components/ui";
import { downloadProtected, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatDate, formatRupees, label } from "@/lib/format";
import { CONTACT_STATUSES, type ContactListItem, type FieldDef, type Page } from "@/lib/types";
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
  const { plural, contactLabel } = useBranding();
  const manager = hasRole(...MANAGER_ROLES);
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [owner, setOwner] = useState(""); // "", "mine", "unassigned"
  const importBatchId = params.get("importBatchId");
  const [page, setPage] = useState(0);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

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
    assignedToId: owner === "mine" ? user?.id : undefined,
    unassignedOnly: owner === "unassigned" || undefined,
    importBatchId: importBatchId ?? undefined,
  };
  const { data, error, loading } = useApi<Page<ContactListItem>>("/api/contacts", { ...query, page, size: 25 });
  const fields = useApi<FieldDef[]>("/api/fields");
  // The first few custom fields become columns, so an imported sheet looks familiar.
  const columns = (fields.data ?? []).filter((f) => f.active).slice(0, 3);

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

  const chip = (active: boolean, text: string, onClick: () => void) => (
    <button
      onClick={onClick}
      className={cx("shrink-0 rounded-full border px-3 py-1 text-xs", active ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}
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
                <Button variant="secondary" loading={exporting} onClick={exportCsv}>
                  Export
                </Button>
                <ButtonLink href="/import" variant="secondary">
                  Import from Excel
                </ButtonLink>
              </>
            )}
            <ButtonLink href="/contacts/new">Add {contactLabel.toLowerCase()}</ButtonLink>
          </>
        }
      />
      <div className="mb-3 grid gap-2 sm:grid-cols-[1fr_14rem]">
        <Input aria-label="Search" placeholder="Search name, phone, email or any field" value={text} onChange={(e) => setText(e.target.value)} />
        <Select
          aria-label="Status"
          value={status}
          placeholder="Any status"
          options={[...CONTACT_STATUSES]}
          labelFor={label}
          onChange={(e) => {
            setStatus(e.target.value);
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
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.items.length ? (
            <EmptyState title={q || status || owner ? "Nothing matches" : `No ${plural.toLowerCase()} yet`}>
              {!q && !status && !owner && manager && (
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
              <Table head={["Name", "Phone", "Status", ...columns.map((c) => c.label), "Assigned to", "Added"]}>
                {data.items.map((c) => (
                  <tr key={c.id}>
                    <Td>
                      <Link href={`/contacts/${c.id}`} className="font-medium text-brand-800 hover:underline">
                        {c.fullName}
                      </Link>
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
