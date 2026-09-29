"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { PhaseBadge } from "@/components/badges";
import { CollegePicker } from "@/components/college-picker";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Textarea } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { countdown, formatDateTime, label } from "@/lib/format";
import { COURSES, QUOTAS, type College } from "@/lib/types";
import { CONFIRMATION_METHODS, type ChoiceList, type ShortlistItem, type Track } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

type Draft = { key: string; collegeId: number; collegeName: string; collegeState: string; course: string; quota: string; note: string };

let seq = 0;
const nextKey = () => `k${++seq}`;

export default function ChoiceListPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const isAdmin = hasRole("SUPER_ADMIN");
  const list = useApi<ChoiceList>(`/api/choice-lists/${id}`);
  const studentId = list.data?.studentId;
  const shortlist = useApi<ShortlistItem[]>(studentId ? `/api/students/${studentId}/shortlist` : null);
  const tracks = useApi<Track[]>(studentId ? `/api/students/${studentId}/counselling` : null);

  const [items, setItems] = useState<Draft[]>([]);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [locking, setLocking] = useState(false);
  const [unlocking, setUnlocking] = useState(false);

  // Load server items into the editable draft whenever the list is (re)loaded.
  const loadedAt = list.data?.updatedAt;
  useEffect(() => {
    if (!list.data) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setItems(
      list.data.items.map((i) => ({
        key: nextKey(),
        collegeId: i.college.id,
        collegeName: i.college.name,
        collegeState: i.college.state,
        course: i.course,
        quota: i.quota,
        note: i.note ?? "",
      })),
    );
    setDirty(false);
  }, [loadedAt, list.data]);

  // Warn before leaving with unsaved changes.
  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, [dirty]);

  if (list.loading && !list.data) return <Loading />;
  if (list.error || !list.data) return <Alert>{list.error ?? "Choice list not found"}</Alert>;
  const cl = list.data;
  const locked = cl.status === "LOCKED";
  const editable = canEdit && !locked;
  const isState = cl.authority.authorityType === "STATE";
  const quotaOptions = QUOTAS.filter((q) => (isState ? q !== "AIQ" && q !== "DEEMED" : q !== "STATE"));

  function update(next: Draft[]) {
    setItems(next);
    setDirty(true);
    setNotice(null);
  }
  function move(i: number, delta: number) {
    const j = i + delta;
    if (j < 0 || j >= items.length) return;
    const next = [...items];
    [next[i], next[j]] = [next[j], next[i]];
    update(next);
  }
  function addChoice(c: { id: number; name: string; state: string }, course: string, quota: string) {
    if (items.some((x) => x.collegeId === c.id && x.course === course && x.quota === quota)) {
      setError(`${c.name} (${course}, ${quota}) is already in the list.`);
      return;
    }
    setError(null);
    update([...items, { key: nextKey(), collegeId: c.id, collegeName: c.name, collegeState: c.state, course, quota, note: "" }]);
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      list.setData(
        await api<ChoiceList>(`/api/choice-lists/${id}/items`, {
          method: "PUT",
          body: { items: items.map((x) => ({ collegeId: x.collegeId, course: x.course, quota: x.quota, note: x.note || null })) },
        }),
      );
      setNotice("Saved.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  }

  const otherLists = (tracks.data?.find((t) => t.id === cl.trackId)?.choiceLists ?? []).filter((l) => l.id !== cl.id && l.items > 0);
  const shortlistOptions = (shortlist.data ?? []).filter((s) => quotaOptions.includes(s.quota as (typeof QUOTAS)[number]) && (!isState || s.state === cl.authority.state));

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href={`/students/${cl.studentId}?tab=counselling`} className="text-ink-soft hover:text-ink">
          ← {cl.studentName}
        </Link>
      </div>
      <PageHeader
        title={`Choice list · ${cl.round.label}`}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone={locked ? "green" : "amber"}>{label(cl.status)}</Badge>
            <PhaseBadge phase={cl.round.phase} />
            {cl.round.choiceFillingEnd && (
              <span>
                Choice filling closes {formatDateTime(cl.round.choiceFillingEnd)} ({countdown(cl.round.choiceFillingEnd)})
              </span>
            )}
          </span>
        }
        actions={
          editable && (
            <>
              <Button variant="secondary" onClick={save} loading={saving} disabled={!dirty}>
                Save order
              </Button>
              <Button onClick={() => setLocking(true)} disabled={dirty || items.length === 0} title={dirty ? "Save first" : undefined}>
                Lock final list
              </Button>
            </>
          )
        }
      />
      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {notice && (
        <div className="mb-4">
          <Alert tone="green">{notice}</Alert>
        </div>
      )}
      {dirty && (
        <div className="mb-4">
          <Alert tone="amber">You have unsaved changes.</Alert>
        </div>
      )}
      {locked && (
        <div className="mb-4">
          <Alert tone="green">
            Locked {formatDateTime(cl.lockedAt)} by {cl.lockedBy?.fullName}. Confirmed by <b>{cl.confirmedByName}</b> ({label(cl.confirmationMethod)}). This exact
            order is stored for dispute protection.
            {isAdmin && (
              <Button size="sm" variant="secondary" className="ml-2" onClick={() => setUnlocking(true)}>
                Unlock
              </Button>
            )}
          </Alert>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
        <Card title={`Preferences (${items.length})`}>
          {items.length === 0 ? (
            <EmptyState title="No choices yet">{editable ? "Add colleges from the panel, most preferred first." : "Nothing was added."}</EmptyState>
          ) : (
            <ol className="space-y-2">
              {items.map((x, i) => (
                <li key={x.key} className="flex gap-3 rounded-lg border border-line p-2.5">
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-brand-50 text-sm font-semibold text-brand-800 tabular-nums">{i + 1}</span>
                  <div className="min-w-0 flex-1">
                    <p className="font-medium">{x.collegeName}</p>
                    <p className="text-xs text-ink-soft">
                      {x.course} · {label(x.quota)} · {x.collegeState}
                    </p>
                    {editable ? (
                      <Input
                        aria-label={`Note for choice ${i + 1}`}
                        placeholder="Note (optional)"
                        maxLength={300}
                        value={x.note}
                        onChange={(e) => update(items.map((y) => (y.key === x.key ? { ...y, note: e.target.value } : y)))}
                        className="mt-1.5 py-1 text-xs"
                      />
                    ) : (
                      x.note && <p className="mt-1 text-xs text-ink-soft">{x.note}</p>
                    )}
                  </div>
                  {editable && (
                    <div className="flex shrink-0 flex-col gap-1 sm:flex-row sm:items-start">
                      <Button size="sm" variant="secondary" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
                        ↑
                      </Button>
                      <Button size="sm" variant="secondary" aria-label="Move down" disabled={i === items.length - 1} onClick={() => move(i, 1)}>
                        ↓
                      </Button>
                      <Button size="sm" variant="ghost" aria-label="Remove" onClick={() => update(items.filter((y) => y.key !== x.key))}>
                        ✕
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ol>
          )}
        </Card>

        {editable && (
          <div className="space-y-6">
            <AddChoice isState={isState} state={cl.authority.state} quotaOptions={quotaOptions} onAdd={addChoice} />
            {shortlistOptions.length > 0 && (
              <Card title="From shortlist">
                <ul className="space-y-1.5">
                  {shortlistOptions.map((s) => {
                    const added = items.some((x) => x.collegeId === s.collegeId && x.course === s.course && x.quota === s.quota);
                    return (
                      <li key={s.id} className="flex items-center justify-between gap-2 text-sm">
                        <span className="min-w-0">
                          <span className="block truncate">{s.collegeName}</span>
                          <span className="text-xs text-ink-soft">
                            {s.course} · {s.quota}
                            {s.band && ` · ${label(s.band)}`}
                          </span>
                        </span>
                        <Button size="sm" variant="secondary" disabled={added} onClick={() => addChoice({ id: s.collegeId, name: s.collegeName, state: s.state }, s.course, s.quota)}>
                          {added ? "Added" : "Add"}
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              </Card>
            )}
            {otherLists.length > 0 && (
              <Card title="Start from another round">
                <div className="space-y-2">
                  {otherLists.map((l) => (
                    <Button
                      key={l.id}
                      variant="secondary"
                      size="sm"
                      className="w-full"
                      onClick={async () => {
                        if (items.length && !confirm("Replace the current choices with this round's list?")) return;
                        try {
                          list.setData(await api<ChoiceList>(`/api/choice-lists/${id}/copy-from/${l.id}`, { method: "POST" }));
                          setNotice(`Copied ${l.items} choices from ${l.roundLabel}.`);
                        } catch (e) {
                          setError(errorMessage(e));
                        }
                      }}
                    >
                      Copy {l.roundLabel} ({l.items})
                    </Button>
                  ))}
                </div>
              </Card>
            )}
          </div>
        )}
      </div>

      <Modal open={locking} onClose={() => setLocking(false)} title="Lock final choice list">
        {locking && (
          <LockForm
            listId={cl.id}
            count={items.length}
            onDone={(updated) => {
              setLocking(false);
              list.setData(updated);
            }}
          />
        )}
      </Modal>
      <Modal open={unlocking} onClose={() => setUnlocking(false)} title="Unlock choice list">
        {unlocking && (
          <UnlockForm
            listId={cl.id}
            onDone={(updated) => {
              setUnlocking(false);
              list.setData(updated);
            }}
          />
        )}
      </Modal>
    </>
  );
}

function AddChoice({
  isState,
  state,
  quotaOptions,
  onAdd,
}: {
  isState: boolean;
  state: string | null;
  quotaOptions: readonly string[];
  onAdd: (c: { id: number; name: string; state: string }, course: string, quota: string) => void;
}) {
  const [college, setCollege] = useState<College | null>(null);
  const [course, setCourse] = useState("MBBS");
  const [quota, setQuota] = useState(quotaOptions[0]);
  return (
    <Card title="Add a choice">
      <div className="space-y-3">
        <CollegePicker value={college} onChange={setCollege} state={isState ? state : null} />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Course">{(id) => <Select id={id} value={course} onChange={(e) => setCourse(e.target.value)} options={COURSES} />}</Field>
          <Field label="Quota">{(id) => <Select id={id} value={quota} onChange={(e) => setQuota(e.target.value)} options={quotaOptions} />}</Field>
        </div>
        <Button
          className="w-full"
          disabled={!college}
          onClick={() => {
            onAdd(college!, course, quota);
            setCollege(null);
          }}
        >
          Add to list
        </Button>
      </div>
    </Card>
  );
}

function LockForm({ listId, count, onDone }: { listId: number; count: number; onDone: (l: ChoiceList) => void }) {
  const [name, setName] = useState("");
  const [method, setMethod] = useState<string>("PHONE");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          onDone(await api<ChoiceList>(`/api/choice-lists/${listId}/lock`, { body: { confirmedByName: name, confirmationMethod: method } }));
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Alert tone="amber">
        Lock only after the student/parent has approved the final order of all {count} choices, exactly as it will be submitted to the authority. After locking,
        only an admin can change it.
      </Alert>
      <Field label="Confirmed by (name and relation)" required hint="e.g. Priya (student) and R. Kumar (father)">
        {(id) => <Input id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label="How they confirmed">{(id) => <Select id={id} value={method} onChange={(e) => setMethod(e.target.value)} options={CONFIRMATION_METHODS} labelFor={label} />}</Field>
      <Button type="submit" loading={saving}>
        Lock list
      </Button>
    </form>
  );
}

function UnlockForm({ listId, onDone }: { listId: number; onDone: (l: ChoiceList) => void }) {
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        try {
          onDone(await api<ChoiceList>(`/api/choice-lists/${listId}/unlock`, { body: { reason } }));
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="Reason" required hint="Recorded in the audit log with the previously locked order">
        {(id) => <Textarea id={id} required rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
      </Field>
      <Button type="submit" variant="danger" loading={saving}>
        Unlock
      </Button>
    </form>
  );
}
