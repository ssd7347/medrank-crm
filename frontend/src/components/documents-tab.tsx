"use client";

import { useRef, useState } from "react";

import { api, errorMessage, openProtectedFile } from "@/lib/api";
import { formatDate, formatDateTime, label } from "@/lib/format";
import { DOCUMENT_STATUSES, type Checklist, type ChecklistItem, type DocumentStatus } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, Card, Input, Loading, Select, Textarea, cx, type Tone } from "./ui";

const STATUS_TONE: Record<DocumentStatus, Tone> = {
  NOT_COLLECTED: "gray",
  COLLECTED: "amber",
  VERIFIED: "green",
  SUBMITTED: "teal",
  REJECTED: "red",
};

export function DocStatusBadge({ status }: { status: DocumentStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{label(status)}</Badge>;
}

/** Student document checklist (spec 4.7). */
export function DocumentsTab({ studentId }: { studentId: number }) {
  const { data, error, loading, setData } = useApi<Checklist>(`/api/students/${studentId}/documents`);
  const [showOptional, setShowOptional] = useState(false);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Could not load documents"}</Alert>;

  const pct = data.requiredCount ? Math.round((100 * data.requiredDone) / data.requiredCount) : 100;
  const required = data.items.filter((i) => i.required);
  const optional = data.items.filter((i) => !i.required);

  return (
    <div className="space-y-6">
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm font-medium">
              {data.requiredDone} of {data.requiredCount} required documents verified
            </p>
            {data.missingRequired.length > 0 && <p className="mt-0.5 text-xs text-ink-soft">Still needed: {data.missingRequired.join(", ")}</p>}
          </div>
          <span className={cx("text-2xl font-semibold tabular-nums", pct === 100 ? "text-emerald-700" : "text-ink")}>{pct}%</span>
        </div>
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
          <div className={cx("h-full rounded-full", pct === 100 ? "bg-emerald-500" : "bg-brand-500")} style={{ width: `${pct}%` }} />
        </div>
      </Card>

      <div className="grid gap-3 lg:grid-cols-2">
        {required.map((item) => (
          <DocumentCard key={item.typeId} item={item} studentId={studentId} onChanged={setData} />
        ))}
      </div>

      {optional.length > 0 && (
        <div>
          <Button variant="ghost" size="sm" onClick={() => setShowOptional((s) => !s)}>
            {showOptional ? "Hide" : "Show"} optional documents ({optional.length})
          </Button>
          {showOptional && (
            <div className="mt-3 grid gap-3 lg:grid-cols-2">
              {optional.map((item) => (
                <DocumentCard key={item.typeId} item={item} studentId={studentId} onChanged={setData} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function DocumentCard({ item, studentId, onChanged }: { item: ChecklistItem; studentId: number; onChanged: (c: Checklist) => void }) {
  const [editing, setEditing] = useState(false);
  const [status, setStatus] = useState<DocumentStatus>(item.status);
  const [validUntil, setValidUntil] = useState(item.validUntil ?? "");
  const [notes, setNotes] = useState(item.notes ?? "");
  const [busy, setBusy] = useState<"save" | "upload" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function save() {
    setBusy("save");
    setError(null);
    try {
      onChanged(await api<Checklist>(`/api/students/${studentId}/documents/${item.typeId}`, { method: "PUT", body: { status, validUntil: validUntil || null, notes } }));
      setEditing(false);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function upload(file: File) {
    if (file.size > 10 * 1024 * 1024) {
      setError("Files can be at most 10 MB.");
      return;
    }
    setBusy("upload");
    setError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      onChanged(await api<Checklist>(`/api/students/${studentId}/documents/${item.typeId}/files`, { method: "POST", form }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
      if (fileRef.current) fileRef.current.value = "";
    }
  }

  return (
    <article className={cx("rounded-xl border bg-surface p-4 shadow-sm", item.expired ? "border-red-300" : "border-line")}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-medium">{item.name}</h3>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs">
            <DocStatusBadge status={item.status} />
            {!item.required && <Badge>Optional</Badge>}
            {item.expired && <Badge tone="red">Expired {formatDate(item.validUntil)}</Badge>}
            {item.expiringSoon && <Badge tone="amber">Expires {formatDate(item.validUntil)}</Badge>}
            {!item.expired && !item.expiringSoon && item.validUntil && <span className="text-ink-soft">Valid until {formatDate(item.validUntil)}</span>}
          </div>
          {item.verifiedBy && (
            <p className="mt-1 text-xs text-ink-faint">
              Verified by {item.verifiedBy.fullName} · {formatDateTime(item.verifiedAt)}
            </p>
          )}
          {item.notes && !editing && <p className="mt-1 text-xs text-ink-soft">{item.notes}</p>}
        </div>
      </div>

      {error && (
        <div className="mt-2">
          <Alert>{error}</Alert>
        </div>
      )}

      {item.files.length > 0 && (
        <ul className="mt-3 space-y-1">
          {item.files.map((f, i) => (
            <li key={f.id} className="flex items-center justify-between gap-2 text-xs">
              <button
                type="button"
                onClick={() => openProtectedFile(`/api/document-files/${f.id}`).catch((e) => setError(errorMessage(e)))}
                className={cx("truncate text-left text-brand-700 hover:underline", i > 0 && "text-ink-soft")}
              >
                {f.originalName}
                {i > 0 && " (older)"}
              </button>
              <span className="shrink-0 text-ink-faint">
                {f.fromPortal && "from portal · "}
                {formatDateTime(f.uploadedAt)}
              </span>
            </li>
          ))}
        </ul>
      )}

      {editing ? (
        <div className="mt-3 space-y-2 rounded-lg bg-muted p-3">
          <div className="grid gap-2 sm:grid-cols-2">
            <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value as DocumentStatus)} options={DOCUMENT_STATUSES} labelFor={label} />
            {item.requiresExpiry && <Input aria-label="Valid until" type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />}
          </div>
          <Textarea aria-label="Notes" rows={2} maxLength={500} placeholder="Notes, e.g. original kept in office file" value={notes} onChange={(e) => setNotes(e.target.value)} />
          <div className="flex gap-2">
            <Button size="sm" onClick={save} loading={busy === "save"}>
              Save
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>
            Update status
          </Button>
          <Button size="sm" variant="secondary" loading={busy === "upload"} onClick={() => fileRef.current?.click()}>
            {item.files.length ? "Upload new version" : "Upload scan"}
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept="application/pdf,image/jpeg,image/png"
            className="hidden"
            aria-label={`Upload ${item.name}`}
            onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])}
          />
        </div>
      )}
    </article>
  );
}
