"use client";

import { useState } from "react";

import { PHASE_TONE } from "@/components/badges";
import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Textarea } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { DATA_ROLES, useAuth } from "@/lib/auth";
import { INDIAN_STATES, formatDateTime, fromLocalInput, label, toLocalInput } from "@/lib/format";
import { ROUNDS } from "@/lib/types";
import type { Authority, Round } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

const thisYear = new Date().getFullYear();
const YEARS = [thisYear + 1, thisYear, thisYear - 1, thisYear - 2].map(String);

export default function CalendarPage() {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...DATA_ROLES);
  const [year, setYear] = useState(String(thisYear));
  const authorities = useApi<Authority[]>("/api/counselling/authorities");
  const rounds = useApi<Round[]>("/api/counselling/rounds", { year });
  const [editing, setEditing] = useState<Round | { authorityId: number } | null>(null);
  const [authEditing, setAuthEditing] = useState<Authority | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="Counselling calendar"
        subtitle="Round dates for each authority. Alerts to students and counsellors are driven by these dates, so keep them exact."
        actions={
          <>
            <Select aria-label="Academic year" value={year} onChange={(e) => setYear(e.target.value)} options={YEARS} className="w-28" />
            {canEdit && (
              <Button variant="secondary" onClick={() => setAuthEditing("new")}>
                Add authority
              </Button>
            )}
          </>
        }
      />
      {(authorities.error || rounds.error) && <Alert>{authorities.error ?? rounds.error}</Alert>}
      {authorities.loading && !authorities.data ? (
        <Loading />
      ) : (
        <div className="space-y-6">
          {(authorities.data ?? []).map((a) => {
            const list = (rounds.data ?? []).filter((r) => r.authority.id === a.id);
            return (
              <Card
                key={a.id}
                title={
                  <span className="flex flex-wrap items-center gap-2">
                    {a.name}
                    <Badge tone={a.authorityType === "CENTRAL" ? "indigo" : "teal"}>{a.code}</Badge>
                    {!a.active && <Badge>Inactive</Badge>}
                  </span>
                }
                actions={
                  canEdit && (
                    <>
                      <Button size="sm" variant="ghost" onClick={() => setAuthEditing(a)}>
                        Edit
                      </Button>
                      <Button size="sm" onClick={() => setEditing({ authorityId: a.id })}>
                        Add round
                      </Button>
                    </>
                  )
                }
              >
                {list.length === 0 ? (
                  <EmptyState title={`No ${year} rounds yet`}>{canEdit ? "Add each round as soon as the authority publishes its schedule." : "The data team has not entered this year's schedule yet."}</EmptyState>
                ) : (
                  <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                    {list.map((r) => (
                      <article key={r.id} className="rounded-lg border border-line p-3">
                        <div className="flex items-center justify-between gap-2">
                          <h3 className="font-medium">{label(r.roundType)}</h3>
                          <Badge tone={PHASE_TONE[r.phase]}>{label(r.phase)}</Badge>
                        </div>
                        <dl className="mt-2 space-y-1 text-sm">
                          <Row k="Registration" from={r.registrationStart} to={r.registrationEnd} />
                          <Row k="Choice filling" from={r.choiceFillingStart} to={r.choiceFillingEnd} />
                          <Row k="Result" to={r.resultAt} />
                          <Row k="Reporting" from={r.reportingStart} to={r.reportingEnd} />
                        </dl>
                        {r.notes && <p className="mt-2 text-xs text-ink-soft">{r.notes}</p>}
                        {canEdit && (
                          <Button size="sm" variant="ghost" className="mt-2 -ml-2" onClick={() => setEditing(r)}>
                            Edit dates
                          </Button>
                        )}
                      </article>
                    ))}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing && "id" in editing ? `Edit ${editing.label}` : "Add round"} wide>
        {editing && (
          <RoundForm
            round={"id" in editing ? editing : undefined}
            authorityId={"id" in editing ? editing.authority.id : editing.authorityId}
            year={Number(year)}
            onDone={() => {
              setEditing(null);
              rounds.reload();
            }}
          />
        )}
      </Modal>
      <Modal open={authEditing !== null} onClose={() => setAuthEditing(null)} title={authEditing === "new" ? "Add authority" : "Edit authority"}>
        {authEditing && (
          <AuthorityForm
            authority={authEditing === "new" ? undefined : authEditing}
            onDone={() => {
              setAuthEditing(null);
              authorities.reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function Row({ k, from, to }: { k: string; from?: string | null; to: string | null }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-faint">{k}</dt>
      <dd className="text-right">{from ? `${formatDateTime(from)} → ${formatDateTime(to)}` : formatDateTime(to)}</dd>
    </div>
  );
}

function RoundForm({ round, authorityId, year, onDone }: { round?: Round; authorityId: number; year: number; onDone: () => void }) {
  const [v, setV] = useState({
    roundType: round?.roundType ?? "ROUND_1",
    registrationStart: toLocalInput(round?.registrationStart),
    registrationEnd: toLocalInput(round?.registrationEnd),
    choiceFillingStart: toLocalInput(round?.choiceFillingStart),
    choiceFillingEnd: toLocalInput(round?.choiceFillingEnd),
    resultAt: toLocalInput(round?.resultAt),
    reportingStart: toLocalInput(round?.reportingStart),
    reportingEnd: toLocalInput(round?.reportingEnd),
    notes: round?.notes ?? "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setV({ ...v, [k]: e.target.value });

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body = {
      authorityId,
      academicYear: round?.academicYear ?? year,
      roundType: v.roundType,
      registrationStart: fromLocalInput(v.registrationStart),
      registrationEnd: fromLocalInput(v.registrationEnd),
      choiceFillingStart: fromLocalInput(v.choiceFillingStart),
      choiceFillingEnd: fromLocalInput(v.choiceFillingEnd),
      resultAt: fromLocalInput(v.resultAt),
      reportingStart: fromLocalInput(v.reportingStart),
      reportingEnd: fromLocalInput(v.reportingEnd),
      notes: v.notes || null,
    };
    try {
      if (round) await api(`/api/counselling/rounds/${round.id}`, { method: "PUT", body });
      else await api("/api/counselling/rounds", { body });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  const pair = (title: string, a: keyof typeof v, b: keyof typeof v) => (
    <fieldset className="grid gap-3 sm:grid-cols-2">
      <legend className="mb-1 text-sm font-medium">{title}</legend>
      <Field label="Opens">{(id) => <Input id={id} type="datetime-local" value={v[a]} onChange={set(a)} />}</Field>
      <Field label="Closes">{(id) => <Input id={id} type="datetime-local" value={v[b]} onChange={set(b)} />}</Field>
    </fieldset>
  );

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      {!round && (
        <Field label={`Round (${year})`}>{(id) => <Select id={id} value={v.roundType} onChange={set("roundType")} options={ROUNDS} labelFor={label} />}</Field>
      )}
      {pair("Registration", "registrationStart", "registrationEnd")}
      {pair("Choice filling & locking", "choiceFillingStart", "choiceFillingEnd")}
      <Field label="Result declared">{(id) => <Input id={id} type="datetime-local" value={v.resultAt} onChange={set("resultAt")} className="sm:max-w-xs" />}</Field>
      {pair("Reporting to college", "reportingStart", "reportingEnd")}
      <Field label="Notes">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={v.notes} onChange={set("notes")} />}</Field>
      <p className="text-xs text-ink-faint">Times are in your device&apos;s time zone (IST). Reporting close is used as the decision deadline for allotted seats.</p>
      <Button type="submit" loading={saving}>
        Save round
      </Button>
    </form>
  );
}

function AuthorityForm({ authority, onDone }: { authority?: Authority; onDone: () => void }) {
  const [v, setV] = useState({
    code: authority?.code ?? "",
    name: authority?.name ?? "",
    authorityType: authority?.authorityType ?? "STATE",
    state: authority?.state ?? "",
    website: authority?.website ?? "",
    active: authority?.active ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      if (authority) await api(`/api/counselling/authorities/${authority.id}`, { method: "PUT", body: v });
      else await api("/api/counselling/authorities", { body: v });
      onDone();
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Code" required hint="Short, e.g. KA-KEA" error={fieldErrors.code}>
        {(id) => <Input id={id} required maxLength={30} value={v.code} onChange={(e) => setV({ ...v, code: e.target.value })} />}
      </Field>
      <Field label="Name" required>{(id) => <Input id={id} required maxLength={200} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="Type">
        {(id) => (
          <Select
            id={id}
            value={v.authorityType}
            onChange={(e) => setV({ ...v, authorityType: e.target.value as Authority["authorityType"] })}
            options={[
              { value: "STATE", label: "State counselling authority" },
              { value: "CENTRAL", label: "Central (MCC)" },
            ]}
          />
        )}
      </Field>
      {v.authorityType === "STATE" && (
        <Field label="State" required>{(id) => <Select id={id} required value={v.state} onChange={(e) => setV({ ...v, state: e.target.value })} options={INDIAN_STATES} placeholder="Choose…" />}</Field>
      )}
      <Field label="Website" error={fieldErrors.website}>{(id) => <Input id={id} type="url" value={v.website} onChange={(e) => setV({ ...v, website: e.target.value })} />}</Field>
      <Checkbox label="Active" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
