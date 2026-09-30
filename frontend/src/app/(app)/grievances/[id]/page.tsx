"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Alert, Badge, Button, Card, DescList, Field, Input, Loading, PageHeader, Select, Textarea, cx, type Tone } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { GRIEVANCE_ROLES, useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatRupees, label } from "@/lib/format";
import type { User } from "@/lib/types";
import type { GrievanceActionType, GrievanceStatus, GrievanceView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

const TONE: Record<GrievanceStatus, Tone> = { OPEN: "blue", UNDER_REVIEW: "indigo", ESCALATED: "red", RESOLVED: "green", CLOSED: "gray" };

const ACTIONS: { type: GrievanceActionType; label: string; needs: "details" | "officer" | "status" }[] = [
  { type: "NOTE", label: "Add note", needs: "details" },
  { type: "CONTACTED", label: "Log contact with complainant", needs: "details" },
  { type: "STATUS_CHANGE", label: "Change status / target date", needs: "status" },
  { type: "ASSIGNED", label: "Reassign officer", needs: "officer" },
  { type: "ESCALATED", label: "Escalate to owner", needs: "details" },
  { type: "RESOLVED", label: "Resolve", needs: "details" },
];

export default function GrievancePage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const manager = hasRole(...GRIEVANCE_ROLES);
  const { data: g, error, loading, setData } = useApi<GrievanceView>(`/api/grievances/${id}`);
  const officers = useApi<User[]>(hasRole("SUPER_ADMIN") ? "/api/users" : null);
  const [type, setType] = useState<GrievanceActionType>("NOTE");
  const [details, setDetails] = useState("");
  const [status, setStatus] = useState<GrievanceStatus>("UNDER_REVIEW");
  const [target, setTarget] = useState("");
  const [officer, setOfficer] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  if (loading && !g) return <Loading />;
  if (error || !g) return <Alert>{error ?? "Grievance not found"}</Alert>;
  const action = ACTIONS.find((a) => a.type === type)!;
  const open = g.status !== "RESOLVED" && g.status !== "CLOSED";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      setData(
        await api<GrievanceView>(`/api/grievances/${id}/actions`, {
          body: {
            type,
            details: details || null,
            status: type === "STATUS_CHANGE" ? status : null,
            targetResolutionDate: type === "STATUS_CHANGE" && target ? target : null,
            assignToId: type === "ASSIGNED" && officer ? Number(officer) : null,
          },
        }),
      );
      setDetails("");
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/grievances" className="text-ink-soft hover:text-ink">
          ← Grievance register
        </Link>
      </div>
      <PageHeader
        title={g.referenceNo}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone={TONE[g.status]}>{label(g.status)}</Badge>
            <span>{label(g.category)}</span>
            <span className={cx(g.overdue && "font-semibold text-red-700")}>· target {formatDate(g.targetResolutionDate)}</span>
          </span>
        }
      />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card title="Complaint">
            <DescList
              items={[
                ["Complainant", g.complainantName],
                ["Phone", g.complainantPhone ? <a href={`tel:${g.complainantPhone}`} className="hover:underline">{g.complainantPhone}</a> : null],
                ["Student", g.studentId ? <Link href={`/students/${g.studentId}`} className="text-brand-700 hover:underline">{g.studentName}</Link> : null],
                ["Amount in dispute", g.amountInDispute ? formatRupees(g.amountInDispute) : null],
                ["Received", `${label(g.receivedVia)} · ${formatDateTime(g.receivedAt)}`],
                ["Officer", g.assignedOfficer?.fullName],
                ["From ticket", g.ticketId ? <Link href={`/tickets/${g.ticketId}`} className="text-brand-700 hover:underline">#{g.ticketId}</Link> : null],
              ]}
            />
            <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{g.description}</p>
            {g.resolution && (
              <div className="mt-4">
                <Alert tone="green">
                  <b>Resolution:</b> {g.resolution}
                </Alert>
              </div>
            )}
          </Card>
          <Card title="Audit trail">
            <ol className="relative space-y-4 border-l border-line pl-4">
              {g.trail.map((a) => (
                <li key={a.id} className="relative">
                  <span className={cx("absolute top-1.5 -left-[21px] h-2.5 w-2.5 rounded-full border-2 border-surface", a.type === "ESCALATED" ? "bg-red-600" : a.type === "RESOLVED" ? "bg-emerald-600" : "bg-brand-500")} />
                  <p className="text-sm font-medium">{label(a.type)}</p>
                  {a.details && <p className="text-sm whitespace-pre-wrap text-ink-soft">{a.details}</p>}
                  <p className="text-xs text-ink-faint">
                    {a.actor?.fullName ?? "System"} · {formatDateTime(a.createdAt)}
                  </p>
                </li>
              ))}
            </ol>
            <p className="mt-4 text-xs text-ink-faint">Entries cannot be edited or deleted.</p>
          </Card>
        </div>
        {manager && (
          <Card title="Record an action" className="h-fit">
            <form onSubmit={submit} className="space-y-3">
              {actionError && <Alert>{actionError}</Alert>}
              <Field label="Action">
                {(fid) => (
                  <Select
                    id={fid}
                    value={type}
                    onChange={(e) => setType(e.target.value as GrievanceActionType)}
                    options={ACTIONS.filter((a) => open || a.type === "NOTE").map((a) => ({ value: a.type, label: a.label }))}
                  />
                )}
              </Field>
              {action.needs === "status" && (
                <>
                  <Field label="Status">
                    {(fid) => <Select id={fid} value={status} onChange={(e) => setStatus(e.target.value as GrievanceStatus)} options={["OPEN", "UNDER_REVIEW", "CLOSED"]} labelFor={label} />}
                  </Field>
                  <Field label="New target date (optional)">{(fid) => <Input id={fid} type="date" value={target} onChange={(e) => setTarget(e.target.value)} />}</Field>
                </>
              )}
              {action.needs === "officer" && (
                <Field label="Officer" required>
                  {(fid) => (
                    <Select
                      id={fid}
                      required
                      value={officer}
                      onChange={(e) => setOfficer(e.target.value)}
                      placeholder="Choose…"
                      options={(officers.data ?? []).filter((u) => u.active && (u.role === "GRIEVANCE_OFFICER" || u.role === "SUPER_ADMIN")).map((u) => ({ value: String(u.id), label: `${u.fullName} · ${label(u.role)}` }))}
                    />
                  )}
                </Field>
              )}
              <Field label={type === "RESOLVED" ? "Resolution" : "Details"} required={action.needs === "details"}>
                {(fid) => <Textarea id={fid} required={action.needs === "details"} rows={4} maxLength={4000} value={details} onChange={(e) => setDetails(e.target.value)} />}
              </Field>
              <Button type="submit" loading={busy} variant={type === "ESCALATED" ? "danger" : "primary"}>
                {action.label}
              </Button>
            </form>
          </Card>
        )}
      </div>
    </>
  );
}
