"use client";

import { useState } from "react";

import { ChangeStatusBadge } from "@/components/badges";
import { Alert, Button, Card, EmptyState, Field, Loading, Modal, PageHeader, Pagination, Select, Table, Td, Textarea, cx } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, label } from "@/lib/format";
import type { ChangeRequest, ChangeRequestDetail, ChangeStatus, Page } from "@/lib/types";
import { useApi } from "@/lib/use-api";

const FILTERS: { value: ChangeStatus | ""; label: string }[] = [
  { value: "PENDING", label: "Pending" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
  { value: "", label: "All" },
];

export default function ApprovalsPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const [status, setStatus] = useState<ChangeStatus | "">("PENDING");
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const list = useApi<Page<ChangeRequest>>("/api/change-requests", { status, page, size: 25 });

  return (
    <>
      <PageHeader
        title="Data approvals"
        subtitle={isAdmin ? "Review changes to college, seat-matrix, fee and cutoff data before they go live." : "Changes you and your team have proposed."}
      />
      {notice && (
        <div className="mb-4">
          <Alert tone="green">{notice}</Alert>
        </div>
      )}

      <BulkUpload
        onDone={(r) => {
          setNotice(r.status === "APPROVED" ? `Uploaded and applied: ${r.summary}` : `Uploaded for approval: ${r.summary}`);
          list.reload();
        }}
      />

      <div className="mt-6 mb-3 flex gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.label}
            onClick={() => {
              setStatus(f.value);
              setPage(0);
            }}
            className={cx("rounded-md border px-3 py-1.5 font-medium transition-colors text-xs", status === f.value ? "border-brand-600 bg-brand-600 text-on-brand" : "border-line bg-surface hover:bg-muted")}
          >
            {f.label}
          </button>
        ))}
      </div>
      <Card>
        <div className="-m-4">
          {list.error && (
            <div className="p-3">
              <Alert>{list.error}</Alert>
            </div>
          )}
          {list.loading && !list.data ? (
            <Loading />
          ) : !list.data?.items.length ? (
            <EmptyState title={status === "PENDING" ? "Nothing waiting for review" : "No requests"} />
          ) : (
            <>
              <Table head={["Change", "Type", "Requested by", "When", "Status", ""]}>
                {list.data.items.map((r) => (
                  <tr key={r.id} className="hover:bg-muted/60">
                    <Td className="max-w-md">{r.summary}</Td>
                    <Td className="whitespace-nowrap">
                      {label(r.action)} · {label(r.entityType)}
                    </Td>
                    <Td className="whitespace-nowrap">{r.requestedBy.fullName}</Td>
                    <Td className="whitespace-nowrap text-ink-soft">{formatDateTime(r.requestedAt)}</Td>
                    <Td>
                      <ChangeStatusBadge status={r.status} />
                    </Td>
                    <Td className="text-right">
                      <Button size="sm" variant="secondary" onClick={() => setOpenId(r.id)}>
                        {isAdmin && r.status === "PENDING" ? "Review" : "View"}
                      </Button>
                    </Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />
            </>
          )}
        </div>
      </Card>

      <Modal open={openId !== null} onClose={() => setOpenId(null)} title="Change request" wide>
        {openId !== null && (
          <ReviewPanel
            id={openId}
            canReview={isAdmin}
            onReviewed={(r) => {
              setOpenId(null);
              setNotice(`${label(r.status)}: ${r.summary}`);
              list.reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function ReviewPanel({ id, canReview, onReviewed }: { id: number; canReview: boolean; onReviewed: (r: ChangeRequest) => void }) {
  const { data, error, loading } = useApi<ChangeRequestDetail>(`/api/change-requests/${id}`);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState<"approve" | "reject" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Not found"}</Alert>;
  const r = data.request;

  async function act(kind: "approve" | "reject") {
    setBusy(kind);
    setActionError(null);
    try {
      onReviewed(await api<ChangeRequest>(`/api/change-requests/${id}/${kind}`, { body: { note } }));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  const payload = data.payload as { rows?: unknown[] } | null;
  const rows = Array.isArray(payload?.rows) ? payload.rows : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <ChangeStatusBadge status={r.status} />
        <span className="font-medium">{r.summary}</span>
      </div>
      <p className="text-xs text-ink-faint">
        Requested by {r.requestedBy.fullName} on {formatDateTime(r.requestedAt)}
        {r.reviewedBy && ` · ${label(r.status).toLowerCase()} by ${r.reviewedBy.fullName} on ${formatDateTime(r.reviewedAt)}`}
      </p>
      {r.reviewNote && <Alert tone={r.status === "REJECTED" ? "red" : "blue"}>{r.reviewNote}</Alert>}

      {r.action === "DELETE" ? (
        <Snapshot title="Record to delete" value={data.current} />
      ) : rows ? (
        <div>
          <p className="mb-1 text-xs font-medium text-ink-soft">{rows.length} rows (first 20 shown). Existing rows with the same key are updated.</p>
          <Snapshot title="" value={rows.slice(0, 20)} />
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {r.action === "UPDATE" && <Snapshot title="Current (live)" value={data.current} />}
          <Snapshot title={r.action === "UPDATE" ? "Proposed" : "New record"} value={data.payload} highlight={r.action === "UPDATE" ? data.current : undefined} />
        </div>
      )}

      {canReview && r.status === "PENDING" && (
        <div className="space-y-3 border-t border-line pt-4">
          {actionError && <Alert>{actionError}</Alert>}
          <Field label="Note" hint="Required when rejecting, so the requester knows what to fix">
            {(fid) => <Textarea id={fid} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <div className="flex gap-2">
            <Button onClick={() => act("approve")} loading={busy === "approve"} disabled={busy !== null}>
              Approve & apply
            </Button>
            <Button variant="danger" onClick={() => act("reject")} loading={busy === "reject"} disabled={busy !== null || !note.trim()}>
              Reject
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Shows a record as key/value lines; keys that differ from `highlight` are marked. */
function Snapshot({ title, value, highlight }: { title: string; value: unknown; highlight?: unknown }) {
  if (value === null || value === undefined) return <p className="text-sm text-ink-faint">Record no longer exists.</p>;
  const other = (highlight ?? null) as Record<string, unknown> | null;
  if (Array.isArray(value)) {
    return <pre className="max-h-72 overflow-auto rounded-lg bg-muted p-3 text-xs">{value.map((v) => JSON.stringify(v)).join("\n")}</pre>;
  }
  return (
    <div>
      {title && <p className="mb-1 text-xs font-medium text-ink-soft">{title}</p>}
      <dl className="space-y-1 rounded-lg bg-muted p-3 text-sm">
        {Object.entries(value as Record<string, unknown>)
          .filter(([k]) => !["id", "updatedAt"].includes(k))
          .map(([k, v]) => {
            const changed = other && k in other && JSON.stringify(other[k]) !== JSON.stringify(v);
            return (
              <div key={k} className={cx("grid grid-cols-[10rem_1fr] gap-2", changed && "rounded bg-amber-100 px-1")}>
                <dt className="text-ink-faint">{k}</dt>
                <dd className="break-words">{v === null || v === "" ? "—" : String(v)}</dd>
              </div>
            );
          })}
      </dl>
    </div>
  );
}

function BulkUpload({ onDone }: { onDone: (r: ChangeRequest) => void }) {
  const [kind, setKind] = useState<"seat-matrix" | "cutoffs">("cutoffs");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<Record<string, string> | null>(null);

  async function upload(e: React.FormEvent) {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(null);
    setRowErrors(null);
    try {
      const form = new FormData();
      form.append("file", file);
      onDone(await api<ChangeRequest>(`/api/change-requests/bulk/${kind}`, { form, method: "POST" }));
      setFile(null);
      (e.target as HTMLFormElement).reset();
    } catch (err) {
      setError(errorMessage(err));
      if (err instanceof ApiError && err.body.errors) setRowErrors(err.body.errors);
    } finally {
      setBusy(false);
    }
  }

  const valueCol = kind === "cutoffs" ? "closing_rank" : "seats";
  return (
    <Card title="Bulk upload from CSV">
      <form onSubmit={upload} className="grid items-end gap-3 md:grid-cols-[12rem_1fr_auto]">
        <Field label="Data">
          {(id) => (
            <Select
              id={id}
              value={kind}
              onChange={(e) => setKind(e.target.value as typeof kind)}
              options={[
                { value: "cutoffs", label: "Closing ranks" },
                { value: "seat-matrix", label: "Seat matrix" },
              ]}
            />
          )}
        </Field>
        <input
          type="file"
          accept=".csv,text/csv"
          aria-label="CSV file"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-brand-50 file:px-3 file:py-2 file:text-sm file:font-medium file:text-brand-800"
        />
        <Button type="submit" loading={busy} disabled={!file}>
          Upload
        </Button>
      </form>
      <p className="mt-3 text-xs text-ink-faint">
        Columns: <code>college_code, course, quota, category, pwd, round, academic_year, {valueCol}</code>. Colleges are matched by code. One bad row
        rejects the whole file so nothing is half-applied.
      </p>
      {error && (
        <div className="mt-3 space-y-2">
          <Alert>{error}</Alert>
          {rowErrors && (
            <ul className="max-h-48 overflow-auto rounded-lg bg-muted p-3 text-xs">
              {Object.entries(rowErrors).map(([row, msg]) => (
                <li key={row}>
                  <b>{row}:</b> {msg}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Card>
  );
}
