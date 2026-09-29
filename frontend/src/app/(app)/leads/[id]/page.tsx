"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { LeadStatusBadge } from "@/components/badges";
import { LeadForm } from "@/components/lead-form";
import { StudentForm, emptyStudent } from "@/components/student-form";
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  DescList,
  EmptyState,
  Field,
  Input,
  Loading,
  Modal,
  PageHeader,
  Select,
  Textarea,
  cx,
} from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatNumber, label, relativeTime } from "@/lib/format";
import {
  LEAD_STATUSES,
  MANUAL_ACTIVITY_TYPES,
  type Activity,
  type FollowUp,
  type Lead,
  type LeadStatus,
  type StudentRequest,
  type UserRef,
} from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function LeadDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { user, hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const canConvert = hasRole("SUPER_ADMIN", "COUNSELLOR");

  const lead = useApi<Lead>(`/api/leads/${id}`);
  const activities = useApi<Activity[]>(`/api/leads/${id}/activities`);
  const followUps = useApi<FollowUp[]>(`/api/leads/${id}/follow-ups`);
  const staff = useApi<UserRef[]>(isAdmin ? "/api/users/assignable" : null);

  const [editing, setEditing] = useState(false);
  const [converting, setConverting] = useState(false);
  const [statusOpen, setStatusOpen] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  if (lead.loading && !lead.data) return <Loading />;
  if (lead.error || !lead.data) return <Alert>{lead.error ?? "Lead not found"}</Alert>;
  const l = lead.data;

  async function assign(userId: number | null) {
    setActionError(null);
    try {
      lead.setData(await api<Lead>(`/api/leads/${id}/assign`, { body: { userId } }));
      activities.reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  }

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/leads" className="text-ink-soft hover:text-ink">
          ← Leads
        </Link>
      </div>
      <PageHeader
        title={l.fullName}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <LeadStatusBadge status={l.status} />
            <span>{l.phone}</span>
            <span>· {label(l.source)}</span>
            <span>· added {formatDate(l.createdAt)}</span>
          </span>
        }
        actions={
          !editing && (
            <>
              <Button variant="secondary" onClick={() => setStatusOpen(true)}>
                Change stage
              </Button>
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              {l.studentId ? (
                <ButtonLink href={`/students/${l.studentId}`}>Open student profile</ButtonLink>
              ) : (
                canConvert && <Button onClick={() => setConverting(true)}>Convert to student</Button>
              )}
            </>
          )
        }
      />
      {actionError && (
        <div className="mb-4">
          <Alert>{actionError}</Alert>
        </div>
      )}

      {editing ? (
        <Card title="Edit lead">
          <LeadForm
            initial={l}
            submitLabel="Save changes"
            onCancel={() => setEditing(false)}
            onSubmit={async (req) => {
              lead.setData(await api<Lead>(`/api/leads/${id}`, { method: "PUT", body: req }));
              setEditing(false);
            }}
          />
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            <Card title="Details">
              <DescList
                items={[
                  ["Phone", l.phone],
                  ["Alternate phone", l.altPhone],
                  ["Email", l.email],
                  ["Preferred language", label(l.languagePreference)],
                  ["NEET roll number", l.neetRollNo],
                  ["NEET score / AIR", `${formatNumber(l.neetScore)} / ${formatNumber(l.neetAir)}`],
                  ["Category", l.category],
                  ["Home state", l.homeState],
                  ["Domicile", l.domicileStatus ? label(l.domicileStatus) : null],
                  ["Referral associate", l.referralAssociate?.fullName],
                ]}
              />
              {l.notes && <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{l.notes}</p>}
            </Card>
            <ActivityCard leadId={id} activities={activities.data} loading={activities.loading} onAdded={activities.reload} />
          </div>

          <div className="space-y-6">
            <Card title="Assigned to">
              <p className="text-sm">
                {l.assignedCounsellor ? (
                  <>
                    <span className="font-medium">{l.assignedCounsellor.fullName}</span>{" "}
                    <span className="text-ink-faint">· {label(l.assignedCounsellor.role)}</span>
                  </>
                ) : (
                  <span className="text-amber-700">Nobody yet</span>
                )}
              </p>
              {isAdmin ? (
                <Select
                  className="mt-3"
                  aria-label="Reassign"
                  value={l.assignedCounsellor?.id.toString() ?? ""}
                  onChange={(e) => assign(e.target.value ? Number(e.target.value) : null)}
                  placeholder="Unassigned"
                  options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fullName} · ${label(u.role)}` }))}
                />
              ) : (
                !l.assignedCounsellor && (
                  <Button className="mt-3" size="sm" onClick={() => assign(user!.id)}>
                    Take this lead
                  </Button>
                )
              )}
            </Card>
            <FollowUpCard leadId={id} followUps={followUps.data} loading={followUps.loading} onChanged={followUps.reload} />
          </div>
        </div>
      )}

      <StatusModal
        open={statusOpen}
        lead={l}
        onClose={() => setStatusOpen(false)}
        onSaved={(updated) => {
          lead.setData(updated);
          activities.reload();
          setStatusOpen(false);
        }}
      />

      <Modal open={converting} onClose={() => setConverting(false)} title="Convert to student" wide>
        <p className="mb-4 text-sm text-ink-soft">
          Creates the student&apos;s NEET profile from this lead. Check the category, home state and domicile carefully: they
          drive quota eligibility.
        </p>
        {converting && (
          <StudentForm
            initial={studentFromLead(l)}
            submitLabel="Create student profile"
            onCancel={() => setConverting(false)}
            onSubmit={async (req) => {
              const updated = await api<Lead>(`/api/leads/${id}/convert`, { body: req });
              lead.setData(updated);
              activities.reload();
              setConverting(false);
            }}
          />
        )}
      </Modal>
    </>
  );
}

function studentFromLead(l: Lead): StudentRequest {
  return {
    ...emptyStudent(),
    fullName: l.fullName,
    phone: l.phone,
    parentPhone: l.altPhone,
    email: l.email,
    neetRollNo: l.neetRollNo,
    neetScore: l.neetScore,
    neetAir: l.neetAir,
    category: l.category ?? "GEN",
    homeState: l.homeState ?? "",
    domicileStatus: l.domicileStatus ?? "UNKNOWN",
    languagePreference: l.languagePreference,
    assignedCounsellorId: l.assignedCounsellor?.role === "TELECALLER" ? null : (l.assignedCounsellor?.id ?? null),
  };
}

function StatusModal({ open, lead, onClose, onSaved }: { open: boolean; lead: Lead; onClose: () => void; onSaved: (l: Lead) => void }) {
  const [status, setStatus] = useState<LeadStatus>(lead.status);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      onSaved(await api<Lead>(`/api/leads/${lead.id}/status`, { body: { status, note } }));
      setNote("");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Change stage">
      <form onSubmit={save} className="space-y-4">
        {error && <Alert>{error}</Alert>}
        <div className="grid gap-2">
          {LEAD_STATUSES.map((s) => (
            <label
              key={s}
              className={cx("flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2", status === s ? "border-brand-500 bg-brand-50" : "border-line")}
            >
              <input type="radio" name="status" value={s} checked={status === s} onChange={() => setStatus(s)} className="accent-brand-600" />
              <LeadStatusBadge status={s} />
              {s === lead.status && <span className="text-xs text-ink-faint">current</span>}
            </label>
          ))}
        </div>
        {!lead.studentId && (status === "ACTIVELY_COUNSELLED" || status === "ADMISSION_CONFIRMED") && (
          <Alert tone="amber">Convert this lead to a student first.</Alert>
        )}
        <Field label="Note (optional)">{(id) => <Textarea id={id} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
        <Button type="submit" loading={saving} disabled={status === lead.status}>
          Save stage
        </Button>
      </form>
    </Modal>
  );
}

function ActivityCard({ leadId, activities, loading, onAdded }: { leadId: string; activities: Activity[] | null; loading: boolean; onAdded: () => void }) {
  const [type, setType] = useState<string>("CALL");
  const [outcome, setOutcome] = useState("");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    if (!outcome.trim() && !notes.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await api(`/api/leads/${leadId}/activities`, { body: { type, outcome, notes } });
      setOutcome("");
      setNotes("");
      onAdded();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card title="Activity">
      <form onSubmit={add} className="mb-5 space-y-2 rounded-lg bg-muted p-3">
        {error && <Alert>{error}</Alert>}
        <div className="grid gap-2 sm:grid-cols-[9rem_1fr]">
          <Select aria-label="Activity type" value={type} onChange={(e) => setType(e.target.value)} options={MANUAL_ACTIVITY_TYPES} labelFor={label} />
          <Input aria-label="Outcome" placeholder="Outcome, e.g. Interested, No answer" maxLength={120} value={outcome} onChange={(e) => setOutcome(e.target.value)} />
        </div>
        <Textarea aria-label="Notes" placeholder="Notes" rows={2} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <Button type="submit" size="sm" loading={saving} disabled={!outcome.trim() && !notes.trim()}>
          Log activity
        </Button>
      </form>
      {loading && !activities ? (
        <Loading />
      ) : !activities?.length ? (
        <EmptyState title="No activity yet">Log the first call or note above.</EmptyState>
      ) : (
        <ol className="relative space-y-4 border-l border-line pl-4">
          {activities.map((a) => (
            <li key={a.id} className="relative">
              <span className="absolute top-1.5 -left-[21px] h-2.5 w-2.5 rounded-full border-2 border-surface bg-brand-500" />
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge tone={a.type === "STATUS_CHANGE" || a.type === "ASSIGNMENT" ? "gray" : "teal"}>{label(a.type)}</Badge>
                {a.outcome && <span className="font-medium">{a.type === "STATUS_CHANGE" ? a.outcome.split(" -> ").map(label).join(" → ") : a.outcome}</span>}
              </div>
              {a.notes && <p className="mt-1 text-sm whitespace-pre-wrap text-ink-soft">{a.notes}</p>}
              <p className="mt-0.5 text-xs text-ink-faint">
                {a.createdBy?.fullName ?? "System"} · {formatDateTime(a.createdAt)}
              </p>
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}

function FollowUpCard({ leadId, followUps, loading, onChanged }: { leadId: string; followUps: FollowUp[] | null; loading: boolean; onChanged: () => void }) {
  const [dueAt, setDueAt] = useState("");
  const [purpose, setPurpose] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      // datetime-local has no zone; the browser interprets it in the user's local (IST) time.
      await api(`/api/leads/${leadId}/follow-ups`, { body: { dueAt: new Date(dueAt).toISOString(), purpose } });
      setDueAt("");
      setPurpose("");
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function complete(id: number) {
    try {
      await api(`/api/follow-ups/${id}/complete`, { method: "POST" });
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const open = (followUps ?? []).filter((f) => !f.completedAt);
  const done = (followUps ?? []).filter((f) => f.completedAt);

  return (
    <Card title="Follow-ups">
      <form onSubmit={add} className="mb-4 space-y-2">
        {error && <Alert>{error}</Alert>}
        <Input aria-label="Due at" type="datetime-local" required value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
        <Input aria-label="Purpose" placeholder="Purpose, e.g. Call after Round 1 result" required maxLength={200} value={purpose} onChange={(e) => setPurpose(e.target.value)} />
        <Button type="submit" size="sm" loading={saving}>
          Schedule
        </Button>
      </form>
      {loading && !followUps ? (
        <Loading />
      ) : !followUps?.length ? (
        <p className="text-sm text-ink-faint">None scheduled.</p>
      ) : (
        <ul className="space-y-2">
          {open.map((f) => (
            <li key={f.id} className="rounded-lg border border-line p-2.5">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm">{f.purpose}</p>
                  <p className={cx("text-xs", f.overdue ? "font-medium text-red-700" : "text-ink-faint")}>
                    {formatDateTime(f.dueAt)} ({relativeTime(f.dueAt)}) · {f.assignedTo.fullName}
                  </p>
                </div>
                <Button size="sm" variant="secondary" onClick={() => complete(f.id)}>
                  Done
                </Button>
              </div>
            </li>
          ))}
          {done.map((f) => (
            <li key={f.id} className="px-2.5 text-xs text-ink-faint line-through">
              {f.purpose} · {formatDate(f.dueAt)}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
