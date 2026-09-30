"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { Alert, Badge, Button, Card, EmptyState, Field, Input, Loading, Modal, Select, Textarea, cx, type Tone } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { formatDate, formatDateTime, formatNumber, formatRupees, label } from "@/lib/format";
import { portalApi, usePortal, type PortalDocuments, type PortalMe, type PortalOverview, type PortalTicket } from "@/lib/portal";
import { useNow } from "@/lib/use-now";

const DOC_TONE: Record<string, Tone> = { NOT_COLLECTED: "gray", COLLECTED: "amber", VERIFIED: "green", SUBMITTED: "green", REJECTED: "red" };
const DOC_LABEL: Record<string, string> = {
  NOT_COLLECTED: "Needed",
  COLLECTED: "Received, being checked",
  VERIFIED: "Verified",
  SUBMITTED: "Submitted to college",
  REJECTED: "Not accepted, send again",
};
const TRACK_TONE: Record<string, Tone> = { NOT_REGISTERED: "gray", REGISTERED: "blue", CHOICES_FILLED: "indigo", ALLOTTED: "amber", ADMITTED: "green", EXITED: "gray" };
const DECISION_LABEL: Record<string, string> = { FREEZE: "Accepted (final)", FLOAT: "Accepted, trying for a better seat", WITHDRAW: "Given up" };
const QUESTION_TOPICS = ["GENERAL", "COUNSELLING", "DOCUMENTS", "FEES", "OTHER"];

function timeLeft(iso: string, now: number): string {
  const ms = new Date(iso).getTime() - now;
  if (ms <= 0) return "now";
  const mins = Math.floor(ms / 60_000);
  const d = Math.floor(mins / 1440);
  const h = Math.floor((mins % 1440) / 60);
  if (d >= 2) return `${d} days`;
  if (d === 1) return `1 day ${h} h`;
  if (h >= 1) return `${h} h ${mins % 60} min`;
  return `${Math.max(mins, 1)} min`;
}

export default function PortalHome() {
  const { displayName, loading, logout } = usePortal();
  const router = useRouter();
  const [me, setMe] = useState<PortalMe | null>(null);
  const [studentId, setStudentId] = useState<number | null>(null);
  const [data, setData] = useState<PortalOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [changingPassword, setChangingPassword] = useState(false);

  useEffect(() => {
    if (!loading && !displayName) router.replace("/portal/login");
  }, [loading, displayName, router]);

  useEffect(() => {
    if (!displayName) return;
    portalApi<PortalMe>("/api/portal/me")
      .then((m) => {
        setMe(m);
        setStudentId((cur) => cur ?? m.students[0]?.id ?? null);
      })
      .catch((e) => setError(errorMessage(e)));
  }, [displayName]);

  const load = useCallback(() => {
    if (studentId === null) return;
    portalApi<PortalOverview>(`/api/portal/students/${studentId}`)
      .then((o) => {
        setData(o);
        setError(null);
      })
      .catch((e) => setError(errorMessage(e)));
  }, [studentId]);
  useEffect(load, [load]);

  if (loading || !displayName) {
    return (
      <main className="min-h-screen">
        <Loading />
      </main>
    );
  }

  const current = data && data.student.id === studentId ? data : null;

  return (
    <div className="min-h-screen bg-muted">
      <header className="sticky top-0 z-20 border-b border-line bg-surface">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-3 px-4 py-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold">{me?.orgName ?? "Portal"}</p>
            <p className="truncate text-xs text-ink-soft">{displayName}</p>
          </div>
          <div className="flex shrink-0 gap-1.5">
            <Button size="sm" variant="ghost" onClick={() => setChangingPassword(true)}>
              Password
            </Button>
            <Button size="sm" variant="secondary" onClick={() => logout().then(() => router.replace("/portal/login"))}>
              Sign out
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-3xl space-y-5 px-4 py-5">
        {error && <Alert>{error}</Alert>}
        {me && me.students.length > 1 && (
          <div className="flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Student">
            {me.students.map((s) => (
              <button
                key={s.id}
                role="tab"
                aria-selected={s.id === studentId}
                onClick={() => setStudentId(s.id)}
                className={cx(
                  "shrink-0 rounded-full border px-3 py-1.5 text-sm",
                  s.id === studentId ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface",
                )}
              >
                {s.fullName}
              </button>
            ))}
          </div>
        )}
        {me && me.students.length === 0 && (
          <Card>
            <EmptyState title="No student is linked to this login">Please call your counsellor.</EmptyState>
          </Card>
        )}
        {!current ? (
          studentId !== null && !error && <Loading />
        ) : (
          <Overview data={current} onChanged={load} setData={setData} />
        )}
      </main>

      <Modal open={changingPassword} onClose={() => setChangingPassword(false)} title="Change password">
        {changingPassword && (
          <PasswordForm
            onDone={() => {
              setChangingPassword(false);
              // Changing the password signs out every device, including this one.
              logout().then(() => router.replace("/portal/login"));
            }}
          />
        )}
      </Modal>
    </div>
  );
}

function Overview({ data, onChanged, setData }: { data: PortalOverview; onChanged: () => void; setData: (o: PortalOverview) => void }) {
  const now = useNow(30_000);
  const s = data.student;
  const next = data.nextDeadline;
  const hoursLeft = next ? (new Date(next.at).getTime() - now) / 3_600_000 : null;
  const [asking, setAsking] = useState(false);

  return (
    <>
      <section>
        <h1 className="text-xl font-semibold tracking-tight">{s.fullName}</h1>
        <p className="mt-0.5 text-sm text-ink-soft">
          {s.category} · {s.homeState}
          {s.neetScore !== null && ` · NEET ${s.neetScore}/720`}
          {s.neetAir !== null && ` · AIR ${formatNumber(s.neetAir)}`}
        </p>
      </section>

      <section
        aria-label="Next deadline"
        className={cx(
          "rounded-2xl border p-5 shadow-sm",
          !next ? "border-line bg-surface" : hoursLeft! <= 48 ? "border-red-300 bg-red-50" : "border-brand-200 bg-brand-50",
        )}
      >
        {next ? (
          <>
            <p className="text-xs font-medium tracking-wide text-ink-soft uppercase">Next deadline</p>
            <p className="mt-1 text-base font-semibold">{next.title}</p>
            <p className={cx("mt-2 text-3xl font-semibold tabular-nums", hoursLeft! <= 48 && "text-red-700")}>{timeLeft(next.at, now)} left</p>
            <p className="mt-1 text-sm text-ink-soft">
              {formatDateTime(next.at)} · {next.detail}
            </p>
          </>
        ) : (
          <>
            <p className="text-base font-semibold">No deadline coming up</p>
            <p className="mt-1 text-sm text-ink-soft">Your counsellor will tell you when the next round dates are announced.</p>
          </>
        )}
      </section>

      {s.counsellorName && (
        <section className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line bg-surface p-4 shadow-sm">
          <div>
            <p className="text-xs text-ink-faint">Your counsellor</p>
            <p className="text-sm font-medium">{s.counsellorName}</p>
          </div>
          {s.counsellorPhone && (
            <a href={`tel:${s.counsellorPhone}`} className="rounded-lg bg-brand-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-700">
              Call {s.counsellorPhone}
            </a>
          )}
        </section>
      )}

      <Card title="Counselling status">
        {!data.tracks.length ? (
          <EmptyState title="Not started yet">Your counsellor will register you for All India and State counselling.</EmptyState>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {data.tracks.map((t) => (
              <article key={`${t.authorityName}${t.academicYear}`} className="rounded-lg border border-line p-3">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-sm font-semibold">
                    {t.authorityType === "STATE" ? "State counselling" : "All India counselling"}
                    <span className="block text-xs font-normal text-ink-soft">
                      {t.authorityName} · {t.academicYear}
                    </span>
                  </h3>
                  <Badge tone={TRACK_TONE[t.status] ?? "gray"}>{label(t.status)}</Badge>
                </div>
                {t.currentRound && (
                  <p className="mt-2 text-xs text-ink-soft">
                    {t.currentRound}
                    {t.currentPhase && `: ${label(t.currentPhase).toLowerCase()}`}
                  </p>
                )}
                {t.registrationNo && <p className="mt-1 text-xs text-ink-faint tabular-nums">Registration no. {t.registrationNo}</p>}
                {t.allotments.length > 0 && (
                  <ul className="mt-3 space-y-2 border-t border-line pt-3">
                    {t.allotments.map((a) => (
                      <li key={a.roundLabel} className="text-sm">
                        <p className="text-xs text-ink-faint">{a.roundLabel}</p>
                        {a.allotted ? (
                          <>
                            <p className="font-medium">{a.collegeName}</p>
                            <p className="text-xs text-ink-soft">
                              {a.course}
                              {a.quota && ` · ${label(a.quota)}`}
                            </p>
                            <p className={cx("mt-0.5 text-xs", a.decision ? "text-emerald-700" : "font-medium text-red-700")}>
                              {a.decision ? (DECISION_LABEL[a.decision] ?? label(a.decision)) : `Decision needed${a.decisionDeadline ? ` by ${formatDateTime(a.decisionDeadline)}` : ""}`}
                            </p>
                          </>
                        ) : (
                          <p className="text-ink-soft">No seat allotted in this round</p>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </article>
            ))}
          </div>
        )}
      </Card>

      <Documents
        studentId={s.id}
        docs={data.documents}
        onUploaded={(d) => setData({ ...data, documents: d })}
      />

      <Card title="Consultancy fees">
        {data.fees.total === 0 ? (
          <p className="text-sm text-ink-soft">No fee plan has been set up yet.</p>
        ) : (
          <>
            <div className="grid grid-cols-3 gap-2 text-center">
              <Figure label="Total" value={formatRupees(data.fees.total)} />
              <Figure label="Paid" value={formatRupees(data.fees.paid)} />
              <Figure label="Balance" value={formatRupees(data.fees.balance)} strong={data.fees.balance > 0} />
            </div>
            <ul className="mt-4 divide-y divide-line">
              {data.fees.instalments.map((i, n) => (
                <li key={n} className="flex items-center justify-between gap-3 py-2 text-sm">
                  <span className="min-w-0">
                    <span className="block truncate">{i.label}</span>
                    <span className="text-xs text-ink-soft">Due {formatDate(i.dueDate)}</span>
                  </span>
                  <span className="shrink-0 text-right">
                    <span className="block tabular-nums">{formatRupees(i.amount)}</span>
                    <Badge tone={i.state === "PAID" ? "green" : i.state === "OVERDUE" ? "red" : "amber"}>
                      {i.state === "PAID" ? "Paid" : i.state === "OVERDUE" ? `Overdue ${formatRupees(i.balance)}` : `Due ${formatRupees(i.balance)}`}
                    </Badge>
                  </span>
                </li>
              ))}
            </ul>
            {data.fees.payments.length > 0 && (
              <details className="mt-3 text-sm">
                <summary className="cursor-pointer text-brand-700">Payments received ({data.fees.payments.length})</summary>
                <ul className="mt-2 space-y-1 text-ink-soft">
                  {data.fees.payments.map((p) => (
                    <li key={p.receiptNo} className="flex justify-between gap-3 tabular-nums">
                      <span>
                        {formatDate(p.paidOn)} · {label(p.method)} · {p.receiptNo}
                      </span>
                      <span>{formatRupees(p.amount)}</span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </>
        )}
      </Card>

      {data.shortlist.length > 0 && (
        <Card title="Colleges shortlisted for you">
          <ul className="divide-y divide-line">
            {data.shortlist.map((c, n) => (
              <li key={n} className="flex items-center justify-between gap-3 py-2 text-sm">
                <span className="min-w-0">
                  <span className="block font-medium">{c.collegeName}</span>
                  <span className="text-xs text-ink-soft">
                    {c.state} · {c.course} · {label(c.quota)}
                  </span>
                </span>
                {c.band && <Badge tone={c.band === "HIGH" ? "green" : c.band === "MODERATE" ? "amber" : "red"}>{label(c.band)} chance</Badge>}
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-ink-faint">Chances are estimates from earlier years&apos; closing ranks, not a guarantee of a seat.</p>
        </Card>
      )}

      <Card title="Your questions" actions={<Button size="sm" onClick={() => setAsking(true)}>Ask a question</Button>}>
        {!data.tickets.length ? (
          <p className="text-sm text-ink-soft">Anything unclear? Ask here and your counsellor will reply. For anything urgent, please call.</p>
        ) : (
          <ul className="divide-y divide-line">
            {data.tickets.map((t: PortalTicket) => (
              <li key={t.id} className="py-2.5 text-sm">
                <div className="flex items-start justify-between gap-2">
                  <span className="font-medium">{t.subject}</span>
                  <Badge tone={t.status === "RESOLVED" || t.status === "CLOSED" ? "green" : "amber"}>
                    {t.status === "RESOLVED" || t.status === "CLOSED" ? "Answered" : t.status === "WAITING_ON_STUDENT" ? "Waiting for you" : "Open"}
                  </Badge>
                </div>
                <p className="text-xs text-ink-faint">Asked {formatDateTime(t.createdAt)}</p>
                {t.resolution && <p className="mt-1 rounded-lg bg-muted p-2 text-sm whitespace-pre-wrap">{t.resolution}</p>}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Modal open={asking} onClose={() => setAsking(false)} title="Ask a question">
        {asking && (
          <QuestionForm
            studentId={s.id}
            onDone={() => {
              setAsking(false);
              onChanged();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function Figure({ label: l, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="rounded-lg bg-muted p-2.5">
      <p className="text-xs text-ink-faint">{l}</p>
      <p className={cx("mt-0.5 text-sm font-semibold tabular-nums sm:text-base", strong && "text-red-700")}>{value}</p>
    </div>
  );
}

function Documents({ studentId, docs, onUploaded }: { studentId: number; docs: PortalDocuments; onUploaded: (d: PortalDocuments) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [target, setTarget] = useState<number | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function upload(file: File) {
    if (target === null) return;
    const typeId = target;
    setError(null);
    setNotice(null);
    if (file.size > 10 * 1024 * 1024) {
      setError("That file is larger than 10 MB. Please send a smaller photo or PDF.");
      return;
    }
    setBusy(typeId);
    try {
      const form = new FormData();
      form.append("file", file);
      onUploaded(await portalApi<PortalDocuments>(`/api/portal/students/${studentId}/documents/${typeId}/files`, { form }));
      setNotice("Received. Our team will check it and update the status here.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card title={`Documents (${docs.requiredDone} of ${docs.requiredCount} ready)`}>
      <div className="space-y-3">
        {error && <Alert>{error}</Alert>}
        {notice && <Alert tone="green">{notice}</Alert>}
        <input
          ref={input}
          type="file"
          accept="application/pdf,image/jpeg,image/png"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = "";
            if (f) upload(f);
          }}
        />
        <ul className="divide-y divide-line">
          {docs.items.map((d) => {
            const done = d.status === "VERIFIED" || d.status === "SUBMITTED";
            return (
              <li key={d.typeId} className="flex items-center justify-between gap-3 py-2.5">
                <span className="min-w-0">
                  <span className="block text-sm">
                    {d.name}
                    {!d.required && <span className="text-ink-faint"> (if applicable)</span>}
                  </span>
                  <span className="mt-0.5 flex flex-wrap items-center gap-1.5">
                    <Badge tone={d.expired ? "red" : (DOC_TONE[d.status] ?? "gray")}>{d.expired ? "Expired, send a new one" : (DOC_LABEL[d.status] ?? label(d.status))}</Badge>
                    {d.validUntil && !d.expired && <span className="text-xs text-ink-faint">valid until {formatDate(d.validUntil)}</span>}
                  </span>
                </span>
                {(!done || d.expired) && (
                  <Button
                    size="sm"
                    variant={d.files && !d.expired && d.status !== "REJECTED" ? "ghost" : "secondary"}
                    loading={busy === d.typeId}
                    onClick={() => {
                      setTarget(d.typeId);
                      input.current?.click();
                    }}
                    className="shrink-0"
                  >
                    {d.files ? "Send again" : "Upload"}
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
        <p className="text-xs text-ink-faint">Clear photo or scan, as PDF, JPG or PNG, up to 10 MB. Keep the originals safe: colleges ask for them at reporting.</p>
      </div>
    </Card>
  );
}

function QuestionForm({ studentId, onDone }: { studentId: number; onDone: () => void }) {
  const [v, setV] = useState({ subject: "", description: "", category: "GENERAL" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await portalApi(`/api/portal/students/${studentId}/questions`, { body: v });
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
      <Alert tone="amber">If a deadline is today or tomorrow, please call your counsellor instead of writing here.</Alert>
      <Field label="About">{(id) => <Select id={id} value={v.category} onChange={(e) => setV({ ...v, category: e.target.value })} options={QUESTION_TOPICS} labelFor={label} />}</Field>
      <Field label="Your question" required>
        {(id) => <Input id={id} required maxLength={200} value={v.subject} onChange={(e) => setV({ ...v, subject: e.target.value })} />}
      </Field>
      <Field label="Details (optional)">{(id) => <Textarea id={id} maxLength={4000} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <Button type="submit" loading={saving}>
        Send
      </Button>
    </form>
  );
}

function PasswordForm({ onDone }: { onDone: () => void }) {
  const [v, setV] = useState({ currentPassword: "", newPassword: "" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await portalApi("/api/portal/change-password", { body: v });
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
      <Field label="Current password" required>
        {(id) => <Input id={id} type="password" autoComplete="current-password" required value={v.currentPassword} onChange={(e) => setV({ ...v, currentPassword: e.target.value })} />}
      </Field>
      <Field label="New password" required hint="At least 8 characters. You will be asked to sign in again.">
        {(id) => (
          <Input id={id} type="password" autoComplete="new-password" required minLength={8} maxLength={72} value={v.newPassword} onChange={(e) => setV({ ...v, newPassword: e.target.value })} />
        )}
      </Field>
      <Button type="submit" loading={saving}>
        Change password
      </Button>
    </form>
  );
}
