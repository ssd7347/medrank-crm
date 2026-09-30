"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";

import { GrievanceForm } from "@/components/grievance-form";
import { PRIORITY_TONE, TICKET_STATUS_TONE } from "@/components/tickets";
import { Alert, Badge, Button, Card, DescList, Field, Loading, Modal, PageHeader, Select, Textarea, cx } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { GRIEVANCE_ROLES, useAuth } from "@/lib/auth";
import { countdown, formatDateTime, label } from "@/lib/format";
import type { UserRef } from "@/lib/types";
import { TICKET_PRIORITIES, TICKET_STATUSES, type TicketView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

export default function TicketPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { user, hasRole } = useAuth();
  const { data: t, error, loading, setData } = useApi<TicketView>(`/api/tickets/${id}`);
  const staff = useApi<UserRef[]>("/api/users/assignable");
  const [comment, setComment] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [priority, setPriority] = useState<string | null>(null);
  const [assignee, setAssignee] = useState<string | null>(null);
  const [resolution, setResolution] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [escalating, setEscalating] = useState(false);

  if (loading && !t) return <Loading />;
  if (error || !t) return <Alert>{error ?? "Ticket not found"}</Alert>;
  const canUpdate = hasRole(...GRIEVANCE_ROLES) || t.assignedTo?.id === user?.id;
  const s = status ?? t.status;
  const closing = s === "RESOLVED" || s === "CLOSED";

  async function run(fn: () => Promise<TicketView>) {
    setBusy(true);
    setActionError(null);
    try {
      setData(await fn());
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/tickets" className="text-ink-soft hover:text-ink">
          ← Helpdesk
        </Link>
      </div>
      <PageHeader
        title={t.subject}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone={TICKET_STATUS_TONE[t.status]}>{label(t.status)}</Badge>
            <Badge tone={PRIORITY_TONE[t.priority]}>{label(t.priority)}</Badge>
            {t.deadlineLinked && <Badge tone="red">Deadline-linked</Badge>}
            <span className={cx(t.overdue && "font-semibold text-red-700")}>
              {t.status === "RESOLVED" || t.status === "CLOSED" ? `Resolved ${formatDateTime(t.resolvedAt)}` : t.overdue ? "Past response deadline" : `Respond ${countdown(t.dueAt)}`}
            </span>
          </span>
        }
        actions={
          t.status !== "CLOSED" && (
            <Button variant="secondary" onClick={() => setEscalating(true)}>
              Escalate to grievance
            </Button>
          )
        }
      />
      {actionError && (
        <div className="mb-4">
          <Alert>{actionError}</Alert>
        </div>
      )}
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card title="Details">
            <DescList
              items={[
                ["Student", t.studentId ? <Link href={`/students/${t.studentId}`} className="text-brand-700 hover:underline">{t.studentName}</Link> : null],
                ["Caller", t.raisedByName],
                ["Came in via", label(t.raisedVia)],
                ["Category", label(t.category)],
                ["Logged by", `${t.createdBy?.fullName ?? "—"} · ${formatDateTime(t.createdAt)}`],
                ["Assigned to", t.assignedTo?.fullName],
              ]}
            />
            {t.description && <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{t.description}</p>}
            {t.resolution && (
              <div className="mt-4">
                <Alert tone="green">
                  <b>Resolution:</b> {t.resolution}
                </Alert>
              </div>
            )}
          </Card>
          <Card title="Conversation">
            <ul className="space-y-3">
              {t.comments.map((c) => (
                <li key={c.id} className="rounded-lg bg-muted p-3">
                  <p className="text-sm whitespace-pre-wrap">{c.body}</p>
                  <p className="mt-1 text-xs text-ink-faint">
                    {c.author?.fullName} · {formatDateTime(c.createdAt)}
                  </p>
                </li>
              ))}
              {!t.comments.length && <p className="text-sm text-ink-faint">No updates yet.</p>}
            </ul>
            <form
              className="mt-4 space-y-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (!comment.trim()) return;
                run(() => api<TicketView>(`/api/tickets/${id}/comments`, { body: { body: comment } })).then(() => setComment(""));
              }}
            >
              <Textarea aria-label="Add an update" rows={2} maxLength={4000} placeholder="Add an update (who you spoke to, what was agreed)" value={comment} onChange={(e) => setComment(e.target.value)} />
              <Button type="submit" size="sm" loading={busy} disabled={!comment.trim()}>
                Add update
              </Button>
            </form>
          </Card>
        </div>
        {canUpdate && (
          <Card title="Update ticket" className="h-fit">
            <div className="space-y-3">
              <Field label="Status">{(fid) => <Select id={fid} value={s} onChange={(e) => setStatus(e.target.value)} options={TICKET_STATUSES} labelFor={label} />}</Field>
              <Field label="Priority">{(fid) => <Select id={fid} value={priority ?? t.priority} onChange={(e) => setPriority(e.target.value)} options={TICKET_PRIORITIES} labelFor={label} />}</Field>
              <Field label="Assigned to">
                {(fid) => (
                  <Select
                    id={fid}
                    value={assignee ?? String(t.assignedTo?.id ?? "")}
                    onChange={(e) => setAssignee(e.target.value)}
                    options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))}
                  />
                )}
              </Field>
              {closing && (
                <Field label="Resolution" required={!t.resolution}>
                  {(fid) => <Textarea id={fid} rows={3} maxLength={2000} value={resolution} onChange={(e) => setResolution(e.target.value)} placeholder={t.resolution ?? "How was it resolved?"} />}
                </Field>
              )}
              <Button
                loading={busy}
                onClick={() =>
                  run(() =>
                    api<TicketView>(`/api/tickets/${id}`, {
                      method: "PUT",
                      body: { status: s, priority: priority ?? t.priority, assignedToId: assignee ? Number(assignee) : (t.assignedTo?.id ?? null), resolution: resolution || null },
                    }),
                  )
                }
              >
                Save
              </Button>
            </div>
          </Card>
        )}
      </div>
      <Modal open={escalating} onClose={() => setEscalating(false)} title="Escalate to grievance register" wide>
        {escalating && (
          <GrievanceForm
            ticketId={t.id}
            studentId={t.studentId ?? undefined}
            initialChannel={t.raisedVia}
            initialDescription={t.description ? `${t.subject}\n\n${t.description}` : t.subject}
            onDone={(g) => router.push(`/grievances/${g.id}`)}
          />
        )}
      </Modal>
    </>
  );
}
