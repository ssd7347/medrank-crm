"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { countdown, formatDateTime, label } from "@/lib/format";
import type { UserRef } from "@/lib/types";
import { CHANNELS, TICKET_CATEGORIES, TICKET_PRIORITIES, type TicketStatus, type TicketView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, Checkbox, EmptyState, Field, Input, Loading, Select, Table, Td, Textarea, cx, type Tone } from "./ui";

export const TICKET_STATUS_TONE: Record<TicketStatus, Tone> = {
  OPEN: "blue",
  IN_PROGRESS: "indigo",
  WAITING_ON_STUDENT: "amber",
  RESOLVED: "green",
  CLOSED: "gray",
};
export const PRIORITY_TONE: Record<string, Tone> = { LOW: "gray", NORMAL: "blue", HIGH: "amber", URGENT: "red" };

export function TicketTable({ tickets, showStudent = true }: { tickets: TicketView[]; showStudent?: boolean }) {
  if (!tickets.length) return <EmptyState title="No tickets" />;
  return (
    <Table head={["Subject", ...(showStudent ? ["Student / caller"] : []), "Priority", "Status", "Respond by", "Assigned to"]}>
      {tickets.map((t) => (
        <tr key={t.id} className={cx(t.overdue && "bg-red-50/60")}>
          <Td>
            <Link href={`/tickets/${t.id}`} className="font-medium text-brand-800 hover:underline">
              {t.subject}
            </Link>
            <span className="block text-xs text-ink-faint">
              #{t.id} · {label(t.category)}
              {t.deadlineLinked && " · deadline-linked"}
            </span>
          </Td>
          {showStudent && <Td>{t.studentName ?? t.raisedByName ?? "—"}</Td>}
          <Td>
            <Badge tone={PRIORITY_TONE[t.priority]}>{label(t.priority)}</Badge>
          </Td>
          <Td>
            <Badge tone={TICKET_STATUS_TONE[t.status]}>{label(t.status)}</Badge>
          </Td>
          <Td className="whitespace-nowrap">
            {t.status === "RESOLVED" || t.status === "CLOSED" ? "—" : <span className={cx(t.overdue && "font-semibold text-red-700")}>{t.overdue ? "Overdue" : countdown(t.dueAt)}</span>}
          </Td>
          <Td>{t.assignedTo?.fullName ?? "—"}</Td>
        </tr>
      ))}
    </Table>
  );
}

export function TicketForm({ studentId, onDone }: { studentId?: number; onDone: (t: TicketView) => void }) {
  const staff = useApi<UserRef[]>("/api/users/assignable");
  const [v, setV] = useState({
    raisedByName: "",
    raisedVia: "PHONE",
    subject: "",
    description: "",
    category: "GENERAL",
    priority: "NORMAL",
    deadlineLinked: false,
    assignedToId: "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setV({ ...v, [k]: e.target.value });

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          onDone(
            await api<TicketView>("/api/tickets", {
              body: { ...v, studentId: studentId ?? null, assignedToId: v.assignedToId ? Number(v.assignedToId) : null, raisedByName: v.raisedByName || null },
            }),
          );
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      {!studentId && (
        <Field label="Caller name" hint="For people who are not yet students">
          {(id) => <Input id={id} maxLength={120} value={v.raisedByName} onChange={set("raisedByName")} />}
        </Field>
      )}
      <Field label="Subject" required>{(id) => <Input id={id} required maxLength={200} value={v.subject} onChange={set("subject")} />}</Field>
      <Field label="Details">{(id) => <Textarea id={id} rows={3} maxLength={4000} value={v.description} onChange={set("description")} />}</Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Category">{(id) => <Select id={id} value={v.category} onChange={set("category")} options={TICKET_CATEGORIES} labelFor={label} />}</Field>
        <Field label="Priority">{(id) => <Select id={id} value={v.priority} onChange={set("priority")} options={TICKET_PRIORITIES} labelFor={label} />}</Field>
        <Field label="Came in via">{(id) => <Select id={id} value={v.raisedVia} onChange={set("raisedVia")} options={CHANNELS} labelFor={label} />}</Field>
      </div>
      <Field label="Assign to" hint="Defaults to the student's counsellor, or you">
        {(id) => <Select id={id} value={v.assignedToId} onChange={set("assignedToId")} placeholder="Default" options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))} />}
      </Field>
      <Checkbox
        label="Touches an open counselling deadline (respond within 4 hours)"
        checked={v.deadlineLinked}
        onChange={(e) => setV({ ...v, deadlineLinked: e.target.checked })}
      />
      <Button type="submit" loading={saving}>
        Create ticket
      </Button>
    </form>
  );
}

export function StudentTickets({ studentId }: { studentId: number }) {
  const { data, error, loading, reload } = useApi<TicketView[]>(`/api/students/${studentId}/tickets`);
  const [adding, setAdding] = useState(false);
  if (loading && !data) return <Loading />;
  return (
    <div className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <div className="flex justify-end">
        <Button onClick={() => setAdding((a) => !a)}>{adding ? "Cancel" : "New ticket"}</Button>
      </div>
      {adding && (
        <div className="group relative overflow-hidden rounded-lg border border-line bg-surface p-5 shadow-card transition duration-300 hover:border-brand-300 hover:shadow-lift">
          <TicketForm
            studentId={studentId}
            onDone={() => {
              setAdding(false);
              reload();
            }}
          />
        </div>
      )}
      <div className="overflow-hidden rounded-lg border border-line bg-surface shadow-card">
        <TicketTable tickets={data ?? []} showStudent={false} />
      </div>
      <p className="text-xs text-ink-faint">Updated {formatDateTime(new Date().toISOString())}</p>
    </div>
  );
}
