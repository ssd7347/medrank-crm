"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";

import { ContactStatusBadge } from "@/components/badges";
import { CallButton, CallHistory } from "@/components/call-button";
import { ContactForm } from "@/components/contact-form";
import { TagEditor } from "@/components/tags";
import { Alert, Badge, Button, Card, DescList, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Textarea, cx } from "@/components/ui";
import { ConsentCard, VoiceCallList } from "@/components/voice";
import { api, downloadProtected, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { useStages } from "@/lib/stages";
import { formatDate, formatDateTime, formatRupees, label, relativeTime } from "@/lib/format";
import {
  MANUAL_ACTIVITY_TYPES,
  type Activity,
  type Contact,
  type ContactStatus,
  type FieldDef,
  type FollowUp,
  type UserRef,
} from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function ContactPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const { plural, has } = useBranding();
  const { stages } = useStages();
  const manager = hasRole(...MANAGER_ROLES);
  const admin = hasRole("SUPER_ADMIN");
  const { data: c, error, loading, setData } = useApi<Contact>(`/api/contacts/${id}`);
  const fields = useApi<FieldDef[]>("/api/fields");
  const activities = useApi<Activity[]>(`/api/contacts/${id}/activities`);
  const followUps = useApi<FollowUp[]>(has("FOLLOW_UPS") ? `/api/contacts/${id}/follow-ups` : null);
  const staff = useApi<UserRef[]>(manager ? "/api/users/assignable" : null);
  const [editing, setEditing] = useState(false);
  const [callsVersion, setCallsVersion] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  if (loading && !c) return <Loading />;
  if (error || !c) return <Alert>{error ?? "Not found"}</Alert>;

  const changeStatus = async (status: ContactStatus) => {
    setActionError(null);
    try {
      setData(await api<Contact>(`/api/contacts/${c.id}/status`, { body: { status } }));
      activities.reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  };

  const assign = async (userId: number | null) => {
    setActionError(null);
    try {
      setData(await api<Contact>(`/api/contacts/${c.id}/assign`, { body: { userId } }));
      activities.reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  };

  const show = (f: FieldDef, v: string) => (f.fieldType === "DATE" ? formatDate(v) : f.fieldType === "AMOUNT" ? formatRupees(Number(v)) : v);
  const customRows: [string, React.ReactNode][] = (fields.data ?? [])
    .filter((f) => c.fields[f.key] !== undefined)
    .map((f) => [f.label, show(f, c.fields[f.key])]);

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/contacts" className="text-ink-soft hover:text-ink">
          ← {plural}
        </Link>
      </div>
      <PageHeader
        title={c.fullName}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <ContactStatusBadge status={c.status} />
            <span>{c.phone}</span>
            {c.source && <span>· {c.source}</span>}
          </span>
        }
        actions={
          !editing && (
            <>
              <CallButton target={{ contactId: c.id }} phone={c.phone} name={c.fullName} onLogged={() => (setCallsVersion((n) => n + 1), activities.reload())} />
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
            </>
          )
        }
      />
      {c.status === "DO_NOT_CALL" && (
        <div className="mb-4">
          <Alert tone="red">Marked &ldquo;Do not call&rdquo;. The AI agent will never call this number; please do not call it either.</Alert>
        </div>
      )}
      {actionError && (
        <div className="mb-4">
          <Alert>{actionError}</Alert>
        </div>
      )}

      {editing ? (
        <Card title="Edit">
          <ContactForm
            existing={c}
            onSaved={(saved) => {
              setData(saved);
              setEditing(false);
              activities.reload();
            }}
            onCancel={() => setEditing(false)}
          />
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
          <div className="min-w-0 space-y-6">
            <Card title="Details">
              <DescList
                items={[
                  ["Phone", c.phone],
                  ["Alternate phone", c.altPhone],
                  ["Email", c.email],
                  ["Source", c.source],
                  ["Branch", c.branch?.name],
                  ["Added", formatDate(c.createdAt)],
                  ...customRows,
                ]}
              />
              {c.importBatchId && (
                <p className="mt-3 text-xs text-ink-faint">
                  From an Excel upload.{" "}
                  <Link href={`/contacts?importBatchId=${c.importBatchId}`} className="text-brand-700 hover:underline">
                    See everyone in it
                  </Link>
                </p>
              )}
              {c.notes && <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{c.notes}</p>}
            </Card>
            <ActivityCard contactId={c.id} activities={activities.data} loading={activities.loading} onAdded={activities.reload} />
            <CallHistory contactId={c.id} version={callsVersion} />
          </div>

          <div className="min-w-0 space-y-6">
            <Card title="Status and owner">
              <div className="space-y-3">
                <Select aria-label="Status" value={c.status} options={stages.map((s) => ({ value: s.key, label: s.label }))} onChange={(e) => changeStatus(e.target.value as ContactStatus)} />
                {manager ? (
                  <Select
                    aria-label="Assigned to"
                    value={c.assignedTo ? String(c.assignedTo.id) : ""}
                    placeholder="Nobody yet"
                    options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))}
                    onChange={(e) => assign(e.target.value ? Number(e.target.value) : null)}
                  />
                ) : (
                  <p className="text-sm">
                    Assigned to <span className="font-medium">{c.assignedTo?.fullName ?? "nobody yet"}</span>
                  </p>
                )}
              </div>
            </Card>
            <Card title="Tags">
              <TagEditor contact={c} onChanged={(saved) => (setData(saved), activities.reload())} />
            </Card>
            {has("FOLLOW_UPS") && <FollowUpCard contactId={c.id} followUps={followUps.data} loading={followUps.loading} onChanged={followUps.reload} />}
            {has("AI_CALLS") && (
              <>
                <ConsentCard path={`/api/contacts/${c.id}/voice-consent`} canEdit />
                <VoiceCallList path={`/api/contacts/${c.id}/voice-calls`} />
              </>
            )}
            {admin && <PersonalDataCard contact={c} />}
            {admin && <EraseCard contact={c} />}
          </div>
        </div>
      )}
    </>
  );
}

function ActivityCard({ contactId, activities, loading, onAdded }: { contactId: number; activities: Activity[] | null; loading: boolean; onAdded: () => void }) {
  const [type, setType] = useState<string>("NOTE");
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
      await api(`/api/contacts/${contactId}/activities`, { body: { type, outcome, notes } });
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
    <Card title="Timeline">
      <form onSubmit={add} className="mb-5 space-y-2 rounded-lg bg-muted p-3">
        {error && <Alert>{error}</Alert>}
        <div className="grid gap-2 sm:grid-cols-[9rem_1fr]">
          <Select aria-label="Kind of entry" value={type} onChange={(e) => setType(e.target.value)} options={MANUAL_ACTIVITY_TYPES} labelFor={label} />
          <Input aria-label="Outcome" placeholder="Outcome, e.g. Wants a quote" maxLength={120} value={outcome} onChange={(e) => setOutcome(e.target.value)} />
        </div>
        <Textarea aria-label="Notes" placeholder="Notes" rows={2} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />
        <Button type="submit" size="sm" loading={saving} disabled={!outcome.trim() && !notes.trim()}>
          Add to timeline
        </Button>
      </form>
      {loading && !activities ? (
        <Loading />
      ) : !activities?.length ? (
        <EmptyState title="Nothing yet">Notes, calls and changes appear here.</EmptyState>
      ) : (
        <ol className="relative space-y-4 border-l border-line pl-4">
          {activities.map((a) => (
            <li key={a.id} className="relative">
              <span className="absolute top-1.5 -left-[21px] h-2.5 w-2.5 rounded-full border-2 border-surface bg-brand-500" />
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge tone={a.type === "STATUS_CHANGE" || a.type === "ASSIGNMENT" || a.type === "IMPORT" || a.type === "MERGE" ? "gray" : "teal"}>{label(a.type)}</Badge>
                {a.outcome && <span className="font-medium">{a.outcome}</span>}
              </div>
              {a.notes && <p className="mt-1 text-sm whitespace-pre-wrap text-ink-soft">{a.notes}</p>}
              <p className="mt-0.5 text-xs text-ink-faint">
                {a.createdBy?.fullName ?? "AI agent / system"} · {formatDateTime(a.createdAt)}
              </p>
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}

function FollowUpCard({ contactId, followUps, loading, onChanged }: { contactId: number; followUps: FollowUp[] | null; loading: boolean; onChanged: () => void }) {
  const [dueAt, setDueAt] = useState("");
  const [purpose, setPurpose] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(`/api/contacts/${contactId}/follow-ups`, { body: { dueAt: new Date(dueAt).toISOString(), purpose } });
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

  // Fixed when the card first renders, so rendering stays pure.
  const [now] = useState(() => Date.now());
  const open = (followUps ?? []).filter((f) => !f.completedAt);
  const done = (followUps ?? []).filter((f) => f.completedAt);

  return (
    <Card title="Follow-ups">
      <form onSubmit={add} className="mb-4 space-y-2">
        {error && <Alert>{error}</Alert>}
        <Input aria-label="Due at" type="datetime-local" required value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
        <Input aria-label="Purpose" placeholder="e.g. Send the price list" required maxLength={200} value={purpose} onChange={(e) => setPurpose(e.target.value)} />
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
                <div className="min-w-0">
                  <p className="text-sm break-words">{f.purpose}</p>
                  <p className={cx("text-xs", new Date(f.dueAt).getTime() < now ? "font-medium text-red-700" : "text-ink-faint")}>
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

/** What the server removed; see ErasureService on the backend. */
type Erased = {
  timelineEntries: number;
  followUps: number;
  callLogs: number;
  aiCallsAnonymised: number;
  consentRecords: number;
  callbacks: number;
  alerts: number;
  campaignPlaces: number;
  importReportsCleaned: number;
  numbersKeptAsHash: number;
};

const count = (n: number, one: string, many = one + "s") => `${n} ${n === 1 ? one : many}`;
const digits = (s: string) => s.replace(/\D/g, "");

/** Admin only: erase a person who asked for their data to be deleted. */
/** A copy of everything the CRM holds about this person, for when they ask for it. Admin only; each download is audited. */
function PersonalDataCard({ contact }: { contact: Contact }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function download() {
    setBusy(true);
    setError(null);
    try {
      await downloadProtected(`/api/contacts/${contact.id}/personal-data`, `personal-data-${contact.id}.json`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Their data">
      <p className="text-sm text-ink-soft">
        If they ask what you keep about them: everything in the CRM, in one file. It includes staff notes and alerts, so read it before sending it to
        them. Each download is in the audit log.
      </p>
      {error && (
        <div className="mt-3">
          <Alert>{error}</Alert>
        </div>
      )}
      <div className="mt-3">
        <Button variant="secondary" size="sm" loading={busy} onClick={download}>
          Download their data
        </Button>
      </div>
    </Card>
  );
}

function EraseCard({ contact }: { contact: Contact }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [phone, setPhone] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Erased | null>(null);
  const typed = digits(phone);
  const matches = typed.length >= 8 && typed.endsWith(digits(contact.phone));

  async function erase() {
    setBusy(true);
    setError(null);
    try {
      setDone(await api<Erased>(`/api/contacts/${contact.id}/erase`, { body: { confirmPhone: phone, reason } }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const close = () => (done ? router.push("/contacts") : (setOpen(false), setPhone(""), setReason(""), setError(null)));

  return (
    <Card title="Erase this person">
      <p className="text-sm text-ink-soft">If they ask for their data to be deleted. Everything about them goes, for good.</p>
      <div className="mt-3">
        <Button variant="danger" size="sm" onClick={() => setOpen(true)}>
          Erase this person&hellip;
        </Button>
      </div>
      <Modal open={open} onClose={close} title={done ? "Erased" : `Erase ${contact.fullName}?`}>
        {done ? (
          <div className="space-y-4 text-sm">
            <Alert tone="green">The person has been erased. The erasure is in the audit log, without their name.</Alert>
            <ul className="list-disc space-y-1 pl-5 text-ink-soft">
              <li>
                Deleted: {count(done.timelineEntries, "timeline entry", "timeline entries")}, {count(done.followUps, "follow-up")}, {count(done.callLogs, "call log entry", "call log entries")},{" "}
                {count(done.consentRecords, "consent record")}, {count(done.callbacks, "call-back")}, {count(done.alerts, "alert")}, {count(done.campaignPlaces, "campaign place")}.
              </li>
              <li>AI calls kept only as numbers for the reports: {done.aiCallsAnonymised}.</li>
              {done.importReportsCleaned > 0 && <li>Upload reports that named them, name removed: {done.importReportsCleaned}.</li>}
              {done.numbersKeptAsHash > 0 && <li>They had said no to AI calls, so their number stays blocked (kept only as a code that cannot be read back).</li>}
            </ul>
            <div className="flex justify-end">
              <Button onClick={close}>Back to the list</Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            {error && <Alert>{error}</Alert>}
            <div className="space-y-2 text-sm text-ink-soft">
              <p>
                <strong className="text-ink">This cannot be undone.</strong> Their details, notes, timeline, follow-ups, call log, consent records, call-backs and alerts are deleted. Their AI calls stay only as numbers for the reports (when, how long, how it ended), without their number or anything that was said.
              </p>
              <p>If they had said no to AI calls, their number is kept as a code that cannot be read back, so a list uploaded later cannot make it callable again.</p>
              <p>Not reached from here: backups, server logs, the phone company&rsquo;s own call records, and anything written about them on other contacts.</p>
            </div>
            <Field label={`Type their phone number (${contact.phone}) to confirm`} required>
              {(id) => <Input id={id} value={phone} inputMode="tel" autoComplete="off" onChange={(e) => setPhone(e.target.value)} />}
            </Field>
            <Field label="Why" hint="Kept in the audit log. Do not write their name." required>
              {(id) => <Input id={id} value={reason} maxLength={200} placeholder="Asked by email on 15 Nov" onChange={(e) => setReason(e.target.value)} />}
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={close}>
                Cancel
              </Button>
              <Button variant="danger" loading={busy} disabled={!matches || !reason.trim()} onClick={erase}>
                Erase for good
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </Card>
  );
}
