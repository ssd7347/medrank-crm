"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, fromLocalInput, toLocalInput } from "@/lib/format";
import { SESSION_MODES, SESSION_STATUSES, type Session, type SessionMode, type SessionStatus } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

import { CallHistory } from "./call-button";
import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, Select, Textarea, type Tone } from "./ui";

const STATUS_TONE: Record<SessionStatus, Tone> = { SCHEDULED: "blue", COMPLETED: "green", CANCELLED: "gray", NO_SHOW: "red" };
const STATUS_LABEL: Record<SessionStatus, string> = { SCHEDULED: "Scheduled", COMPLETED: "Done", CANCELLED: "Cancelled", NO_SHOW: "Did not attend" };
const MODE_LABEL: Record<SessionMode, string> = { VIDEO: "Video call", PHONE: "Phone call", IN_PERSON: "In person" };

export function SessionsTab({ studentId, callsVersion }: { studentId: number; callsVersion?: number }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data, error, loading, reload } = useApi<Session[]>(`/api/students/${studentId}/sessions`);
  const [scheduling, setScheduling] = useState(false);
  const [editing, setEditing] = useState<Session | null>(null);

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card title="Counselling sessions" actions={canEdit && <Button size="sm" onClick={() => setScheduling(true)}>Schedule</Button>}>
        {error && <Alert>{error}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No sessions yet">Schedule a video, phone or in-person consultation. The notes stay on this student&apos;s record.</EmptyState>
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((s) => (
              <li key={s.id} className="py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{s.topic}</span>
                  <Badge tone={STATUS_TONE[s.status]}>{STATUS_LABEL[s.status]}</Badge>
                </div>
                <p className="mt-0.5 text-xs text-ink-soft">
                  {formatDateTime(s.scheduledAt)} · {s.durationMinutes} min · {MODE_LABEL[s.mode]} · with {s.host.fullName}
                </p>
                {s.notes && <p className="mt-1.5 rounded-lg bg-muted p-2 text-sm whitespace-pre-wrap">{s.notes}</p>}
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  {s.meetingUrl && s.status === "SCHEDULED" && (
                    <a href={s.meetingUrl} target="_blank" rel="noopener noreferrer" className="rounded-lg bg-brand-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-brand-700">
                      Join video call
                    </a>
                  )}
                  {s.recordingUrl && (
                    <a href={s.recordingUrl} target="_blank" rel="noopener noreferrer" className="text-xs text-brand-700 hover:underline">
                      Recording
                    </a>
                  )}
                  {canEdit && (
                    <Button size="sm" variant="secondary" onClick={() => setEditing(s)}>
                      {s.status === "SCHEDULED" ? "Add notes / finish" : "Edit"}
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <CallHistory studentId={studentId} version={callsVersion} />

      <Modal open={scheduling} onClose={() => setScheduling(false)} title="Schedule a session">
        {scheduling && (
          <ScheduleForm
            studentId={studentId}
            onDone={() => {
              setScheduling(false);
              reload();
            }}
          />
        )}
      </Modal>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title="Session notes">
        {editing && (
          <SessionForm
            session={editing}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </div>
  );
}

function ScheduleForm({ studentId, onDone }: { studentId: number; onDone: () => void }) {
  const [v, setV] = useState({ mode: "VIDEO" as SessionMode, scheduledAt: "", durationMinutes: "30", topic: "", meetingUrl: "", notifyFamily: true });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(`/api/students/${studentId}/sessions`, {
        body: { ...v, scheduledAt: fromLocalInput(v.scheduledAt), durationMinutes: Number(v.durationMinutes) },
      });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="What is it about" required>
        {(id) => <Input id={id} required maxLength={200} placeholder="e.g. Round 2 choice list review" value={v.topic} onChange={(e) => setV({ ...v, topic: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="How">
          {(id) => <Select id={id} value={v.mode} onChange={(e) => setV({ ...v, mode: e.target.value as SessionMode })} options={SESSION_MODES} labelFor={(m) => MODE_LABEL[m as SessionMode]} />}
        </Field>
        <Field label="When" required>
          {(id) => <Input id={id} type="datetime-local" required value={v.scheduledAt} onChange={(e) => setV({ ...v, scheduledAt: e.target.value })} />}
        </Field>
        <Field label="Minutes" required>
          {(id) => <Input id={id} type="number" required min={10} max={240} step={5} value={v.durationMinutes} onChange={(e) => setV({ ...v, durationMinutes: e.target.value })} />}
        </Field>
      </div>
      {v.mode === "VIDEO" && (
        <Field label="Meeting link" hint="Leave empty to get a free Jitsi Meet link, or paste your Zoom / Google Meet link">
          {(id) => <Input id={id} type="url" maxLength={500} placeholder="https://" value={v.meetingUrl} onChange={(e) => setV({ ...v, meetingUrl: e.target.value })} />}
        </Field>
      )}
      <Checkbox label="Send the invitation to the student and parent" checked={v.notifyFamily} onChange={(e) => setV({ ...v, notifyFamily: e.target.checked })} />
      <p className="text-xs text-ink-faint">The invitation also appears in their portal. WhatsApp delivery needs a provider to be connected.</p>
      <Button type="submit" loading={saving}>
        Schedule
      </Button>
    </form>
  );
}

function SessionForm({ session, onDone }: { session: Session; onDone: () => void }) {
  const [v, setV] = useState({
    status: session.status,
    scheduledAt: toLocalInput(session.scheduledAt),
    durationMinutes: String(session.durationMinutes),
    topic: session.topic,
    meetingUrl: session.meetingUrl ?? "",
    notes: session.notes ?? "",
    recordingConsent: session.recordingConsent,
    recordingUrl: session.recordingUrl ?? "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(`/api/sessions/${session.id}`, {
        method: "PUT",
        body: { ...v, scheduledAt: fromLocalInput(v.scheduledAt), durationMinutes: Number(v.durationMinutes), recordingUrl: v.recordingConsent ? v.recordingUrl : "" },
      });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Topic" required>
        {(id) => <Input id={id} required maxLength={200} value={v.topic} onChange={(e) => setV({ ...v, topic: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Status">
          {(id) => <Select id={id} value={v.status} onChange={(e) => setV({ ...v, status: e.target.value as SessionStatus })} options={SESSION_STATUSES} labelFor={(s) => STATUS_LABEL[s as SessionStatus]} />}
        </Field>
        <Field label="When" required>
          {(id) => <Input id={id} type="datetime-local" required value={v.scheduledAt} onChange={(e) => setV({ ...v, scheduledAt: e.target.value })} />}
        </Field>
        <Field label="Minutes" required>
          {(id) => <Input id={id} type="number" required min={10} max={240} value={v.durationMinutes} onChange={(e) => setV({ ...v, durationMinutes: e.target.value })} />}
        </Field>
      </div>
      <Field label="Notes" hint="What was discussed and agreed. Only staff see this.">
        {(id) => <Textarea id={id} rows={5} maxLength={4000} value={v.notes} onChange={(e) => setV({ ...v, notes: e.target.value })} />}
      </Field>
      {session.mode === "VIDEO" && (
        <>
          <Checkbox label="The family agreed to the session being recorded" checked={v.recordingConsent} onChange={(e) => setV({ ...v, recordingConsent: e.target.checked })} />
          {v.recordingConsent && (
            <Field label="Link to the recording">
              {(id) => <Input id={id} type="url" maxLength={500} placeholder="https://" value={v.recordingUrl} onChange={(e) => setV({ ...v, recordingUrl: e.target.value })} />}
            </Field>
          )}
        </>
      )}
      <Button type="submit" loading={saving}>
        Save
      </Button>
      <p className="text-xs text-ink-faint">
        {MODE_LABEL[session.mode]} with {session.host.fullName}
      </p>
    </form>
  );
}
