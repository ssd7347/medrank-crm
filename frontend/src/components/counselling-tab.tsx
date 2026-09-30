"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { countdown, formatDateTime, label } from "@/lib/format";
import { COURSES, QUOTAS, type College } from "@/lib/types";
import {
  DECISIONS,
  TRACK_STATUSES,
  type Allotment,
  type Authority,
  type Decision,
  type DecisionPreview,
  type Round,
  type Track,
} from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

import { PhaseBadge, TrackStatusBadge } from "./badges";
import { CollegePicker } from "./college-picker";
import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, Select, Textarea, cx } from "./ui";

/** Student profile tab: parallel counselling tracks (AIQ / State), rounds, choice lists, results, decisions. */
export function CounsellingTab({ studentId, studentCategory }: { studentId: number; studentCategory: string }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const tracks = useApi<Track[]>(`/api/students/${studentId}/counselling`);
  const [adding, setAdding] = useState(false);

  if (tracks.loading && !tracks.data) return <Loading />;
  return (
    <div className="space-y-6">
      {tracks.error && <Alert>{tracks.error}</Alert>}
      {canEdit && (
        <div className="flex justify-end">
          <Button onClick={() => setAdding(true)}>Add counselling track</Button>
        </div>
      )}
      {!tracks.data?.length ? (
        <Card>
          <EmptyState title="Not tracking any counselling yet">
            Add a track for each authority the student applies to, e.g. MCC (All India Quota) and their home state.
          </EmptyState>
        </Card>
      ) : (
        tracks.data.map((t) => <TrackCard key={t.id} track={t} canEdit={canEdit} category={studentCategory} onChanged={tracks.reload} />)
      )}
      <Modal open={adding} onClose={() => setAdding(false)} title="Add counselling track">
        {adding && (
          <AddTrackForm
            studentId={studentId}
            onDone={() => {
              setAdding(false);
              tracks.reload();
            }}
          />
        )}
      </Modal>
    </div>
  );
}

function TrackCard({ track, canEdit, category, onChanged }: { track: Track; canEdit: boolean; category: string; onChanged: () => void }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [recording, setRecording] = useState<Round | null>(null);
  const [deciding, setDeciding] = useState<Allotment | null>(null);
  const [editingStatus, setEditingStatus] = useState(false);

  async function openList(roundId: number) {
    setError(null);
    try {
      const list = await api<{ id: number }>(`/api/counselling/tracks/${track.id}/rounds/${roundId}/choice-list`, { method: "POST" });
      router.push(`/choice-lists/${list.id}`);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card
      title={
        <span className="flex flex-wrap items-center gap-2">
          {track.authority.code} {track.academicYear}
          <TrackStatusBadge status={track.status} />
          {track.registrationNo && <span className="text-xs font-normal text-ink-soft">Reg. {track.registrationNo}</span>}
        </span>
      }
      actions={
        canEdit && (
          <Button size="sm" variant="ghost" onClick={() => setEditingStatus(true)}>
            Edit
          </Button>
        )
      }
    >
      {error && (
        <div className="mb-3">
          <Alert>{error}</Alert>
        </div>
      )}
      {track.rounds.length === 0 ? (
        <p className="text-sm text-ink-faint">
          No {track.academicYear} rounds in the calendar for {track.authority.code} yet. Ask the data team to add them.
        </p>
      ) : (
        <ol className="space-y-3">
          {track.rounds.map((r) => {
            const list = track.choiceLists.find((c) => c.roundId === r.id);
            const allot = track.allotments.find((a) => a.roundId === r.id);
            return (
              <li key={r.id} className="rounded-lg border border-line p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{label(r.roundType)}</span>
                    <PhaseBadge phase={r.phase} />
                  </div>
                  <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-soft">
                    {r.choiceFillingEnd && (
                      <span>
                        Choices close {formatDateTime(r.choiceFillingEnd)} <b>({countdown(r.choiceFillingEnd)})</b>
                      </span>
                    )}
                    {r.reportingEnd && (
                      <span>
                        Reporting closes {formatDateTime(r.reportingEnd)} <b>({countdown(r.reportingEnd)})</b>
                      </span>
                    )}
                  </div>
                </div>

                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <div className="rounded-md bg-muted p-2.5 text-sm">
                    <p className="text-xs text-ink-faint">Choice list</p>
                    {list ? (
                      <p>
                        <Badge tone={list.status === "LOCKED" ? "green" : "amber"}>{label(list.status)}</Badge>{" "}
                        {list.items} choices
                      </p>
                    ) : (
                      <p className="text-ink-soft">Not started</p>
                    )}
                    <Button size="sm" variant="secondary" className="mt-2" onClick={() => (list ? router.push(`/choice-lists/${list.id}`) : openList(r.id))} disabled={!list && !canEdit}>
                      {list ? "Open" : "Start choice list"}
                    </Button>
                  </div>
                  <div className="rounded-md bg-muted p-2.5 text-sm">
                    <p className="text-xs text-ink-faint">Result</p>
                    {!allot ? (
                      <p className="text-ink-soft">Not recorded</p>
                    ) : !allot.college ? (
                      <p>No seat allotted</p>
                    ) : (
                      <>
                        <p className="font-medium">{allot.college.name}</p>
                        <p className="text-xs text-ink-soft">
                          {allot.course} · {allot.quota} · {allot.category}
                        </p>
                        {allot.decision ? (
                          <p className="mt-1">
                            <Badge tone={allot.decision === "WITHDRAW" ? "red" : "green"}>{label(allot.decision)}</Badge>
                          </p>
                        ) : (
                          allot.decisionDeadline && (
                            <p className="mt-1 text-xs font-medium text-red-700">Decision due {countdown(allot.decisionDeadline)}</p>
                          )
                        )}
                      </>
                    )}
                    {canEdit && (
                      <div className="mt-2 flex flex-wrap gap-2">
                        <Button size="sm" variant="secondary" onClick={() => setRecording(r)}>
                          {allot ? "Correct result" : "Record result"}
                        </Button>
                        {allot?.college && !allot.decision && (
                          <Button size="sm" onClick={() => setDeciding(allot)}>
                            Record decision
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      )}

      <Modal open={recording !== null} onClose={() => setRecording(null)} title={`Result · ${recording?.label ?? ""}`}>
        {recording && (
          <ResultForm
            track={track}
            round={recording}
            category={category}
            onDone={() => {
              setRecording(null);
              onChanged();
            }}
          />
        )}
      </Modal>
      <Modal open={deciding !== null} onClose={() => setDeciding(null)} title="Record decision">
        {deciding && (
          <DecisionForm
            allotment={deciding}
            onDone={() => {
              setDeciding(null);
              onChanged();
            }}
          />
        )}
      </Modal>
      <Modal open={editingStatus} onClose={() => setEditingStatus(false)} title={`${track.authority.code} ${track.academicYear}`}>
        {editingStatus && (
          <TrackStatusForm
            track={track}
            onDone={() => {
              setEditingStatus(false);
              onChanged();
            }}
          />
        )}
      </Modal>
    </Card>
  );
}

function AddTrackForm({ studentId, onDone }: { studentId: number; onDone: () => void }) {
  const authorities = useApi<Authority[]>("/api/counselling/authorities");
  const [v, setV] = useState({ authorityId: "", academicYear: String(new Date().getFullYear()), registrationNo: "", status: "REGISTERED" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await api(`/api/students/${studentId}/counselling`, {
            body: { authorityId: Number(v.authorityId), academicYear: Number(v.academicYear), registrationNo: v.registrationNo || null, status: v.status },
          });
          onDone();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="Counselling authority" required>
        {(id) => (
          <Select
            id={id}
            required
            value={v.authorityId}
            onChange={(e) => setV({ ...v, authorityId: e.target.value })}
            placeholder="Choose…"
            options={(authorities.data ?? []).filter((a) => a.active).map((a) => ({ value: String(a.id), label: `${a.code} · ${a.name}` }))}
          />
        )}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Year" required>
          {(id) => <Input id={id} type="number" required min={2013} max={2100} value={v.academicYear} onChange={(e) => setV({ ...v, academicYear: e.target.value })} />}
        </Field>
        <Field label="Status">{(id) => <Select id={id} value={v.status} onChange={(e) => setV({ ...v, status: e.target.value })} options={TRACK_STATUSES} labelFor={label} />}</Field>
      </div>
      <Field label="Registration number">{(id) => <Input id={id} maxLength={40} value={v.registrationNo} onChange={(e) => setV({ ...v, registrationNo: e.target.value })} />}</Field>
      <Button type="submit" loading={saving}>
        Add track
      </Button>
    </form>
  );
}

function TrackStatusForm({ track, onDone }: { track: Track; onDone: () => void }) {
  const [status, setStatus] = useState<string>(track.status);
  const [reg, setReg] = useState(track.registrationNo ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        try {
          await api(`/api/counselling/tracks/${track.id}`, { method: "PUT", body: { status, registrationNo: reg || null } });
          onDone();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="Status">{(id) => <Select id={id} value={status} onChange={(e) => setStatus(e.target.value)} options={TRACK_STATUSES} labelFor={label} />}</Field>
      <Field label="Registration number">{(id) => <Input id={id} maxLength={40} value={reg} onChange={(e) => setReg(e.target.value)} />}</Field>
      <Button type="submit" loading={saving}>
        Save
      </Button>
    </form>
  );
}

function ResultForm({ track, round, category, onDone }: { track: Track; round: Round; category: string; onDone: () => void }) {
  const existing = track.allotments.find((a) => a.roundId === round.id);
  const [none, setNone] = useState(existing ? !existing.college : false);
  const [college, setCollege] = useState<College | null>(null);
  const [course, setCourse] = useState<string>(existing?.course ?? "MBBS");
  const [quota, setQuota] = useState<string>(existing?.quota ?? (track.authority.authorityType === "STATE" ? "STATE" : "AIQ"));
  const [cat, setCat] = useState<string>(existing?.category ?? category);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        if (!none && !college) {
          setError("Choose the allotted college, or tick 'No seat allotted'.");
          return;
        }
        setSaving(true);
        setError(null);
        try {
          await api(`/api/counselling/tracks/${track.id}/allotments`, {
            body: none ? { roundId: round.id } : { roundId: round.id, collegeId: college!.id, course, quota, category: cat },
          });
          onDone();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      {existing?.college && <Alert tone="blue">Currently recorded: {existing.college.name}. Saving replaces it and clears any decision.</Alert>}
      <Checkbox label="No seat allotted in this round" checked={none} onChange={(e) => setNone(e.target.checked)} />
      {!none && (
        <>
          <Field label="Allotted college" required>
            {() => <CollegePicker value={college} onChange={setCollege} state={track.authority.authorityType === "STATE" ? track.authority.state : null} />}
          </Field>
          <div className="grid grid-cols-3 gap-3">
            <Field label="Course">{(id) => <Select id={id} value={course} onChange={(e) => setCourse(e.target.value)} options={COURSES} />}</Field>
            <Field label="Quota">{(id) => <Select id={id} value={quota} onChange={(e) => setQuota(e.target.value)} options={QUOTAS} />}</Field>
            <Field label="Allotted under">{(id) => <Select id={id} value={cat} onChange={(e) => setCat(e.target.value)} options={["GEN", "EWS", "OBC", "SC", "ST"]} />}</Field>
          </div>
        </>
      )}
      <p className="text-xs text-ink-faint">Saving sends an urgent WhatsApp to the student and parent and alerts the counsellor.</p>
      <Button type="submit" loading={saving}>
        Save result
      </Button>
    </form>
  );
}

function DecisionForm({ allotment, onDone }: { allotment: Allotment; onDone: () => void }) {
  const [decision, setDecision] = useState<Decision | null>(null);
  const [preview, setPreview] = useState<DecisionPreview | null>(null);
  const [note, setNote] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function choose(d: Decision) {
    setDecision(d);
    setConfirmed(false);
    setPreview(null);
    try {
      setPreview(await api<DecisionPreview>(`/api/allotments/${allotment.id}/decision-preview`, { query: { decision: d } }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm">
        <b>{allotment.college?.name}</b> · {allotment.quota} · {allotment.roundLabel}
      </p>
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-2">
        {DECISIONS.map((d) => (
          <button
            key={d}
            type="button"
            onClick={() => choose(d)}
            className={cx("rounded-lg border px-3 py-2 text-left text-sm", decision === d ? "border-brand-500 bg-brand-50" : "border-line hover:bg-muted")}
          >
            {label(d)}
          </button>
        ))}
      </div>
      {preview && (
        <div className="space-y-3">
          <Alert tone={decision === "WITHDRAW" ? "red" : "amber"}>
            <p className="font-medium">Before confirming, tell the family:</p>
            {decision === "WITHDRAW" && (
              <p className="mt-1 text-xs">{preview.refundRuleFound ? "Based on the recorded refund rule for this seat." : "No refund rule recorded for this seat: general guidance only."}</p>
            )}
            <ul className="mt-1 list-disc space-y-1 pl-5">
              {preview.consequences.map((c) => (
                <li key={c}>{c}</li>
              ))}
            </ul>
          </Alert>
          {preview.pastDeadline && <Alert>The reporting deadline has passed. Only an admin can record this.</Alert>}
          <Field label="Note (who decided, how it was confirmed)">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
          <Checkbox label="The student / parent confirmed this decision after hearing the above" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
          <Button
            variant={decision === "WITHDRAW" ? "danger" : "primary"}
            disabled={!confirmed}
            loading={saving}
            onClick={async () => {
              setSaving(true);
              setError(null);
              try {
                await api(`/api/allotments/${allotment.id}/decision`, { body: { decision, note: note || null } });
                onDone();
              } catch (e) {
                setError(errorMessage(e));
              } finally {
                setSaving(false);
              }
            }}
          >
            Confirm {label(decision!)}
          </Button>
        </div>
      )}
    </div>
  );
}
