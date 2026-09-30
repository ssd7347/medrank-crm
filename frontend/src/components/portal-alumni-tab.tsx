"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import type { Student } from "@/lib/types";
import type { AlumniDetail, PortalAccess, PortalGranted, PortalRelation } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

import { AlumniForm } from "./alumni-form";
import { Alert, Badge, Button, ButtonLink, Card, EmptyState, Loading, Modal } from "./ui";

export function PortalAlumniTab({ student }: { student: Student }) {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <PortalAccessCard student={student} />
      <AlumniCard student={student} />
    </div>
  );
}

function PortalAccessCard({ student }: { student: Student }) {
  const base = `/api/students/${student.id}/portal-access`;
  const { data, error, loading, reload } = useApi<PortalAccess[]>(base);
  const [issued, setIssued] = useState<PortalGranted | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function run(key: string, fn: () => Promise<PortalGranted | PortalAccess>) {
    setBusy(key);
    setActionError(null);
    try {
      const r = await fn();
      if ("access" in r) setIssued(r);
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  const has = (r: PortalRelation) => data?.some((a) => a.relation === r);
  const portalUrl = typeof window === "undefined" ? "/portal" : `${window.location.origin}/portal`;

  return (
    <Card title="Student & parent portal">
      <div className="space-y-4">
        <p className="text-sm text-ink-soft">
          Lets the family see round status, deadlines, documents and fees on their own phone, and upload documents, instead of calling to ask.
        </p>
        {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No portal login yet">Give access below, then pass the one-time code to the family.</EmptyState>
        ) : (
          <ul className="divide-y divide-line rounded-lg border border-line">
            {data.map((a) => (
              <li key={a.accountId} className="p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{a.displayName}</span>
                  <Badge tone="indigo">{label(a.relation)}</Badge>
                  {!a.active ? (
                    <Badge tone="red">Switched off</Badge>
                  ) : a.activated ? (
                    <Badge tone="green">Active</Badge>
                  ) : a.codePending ? (
                    <Badge tone="amber">Waiting for first sign-in</Badge>
                  ) : (
                    <Badge tone="red">Code expired</Badge>
                  )}
                </div>
                <p className="mt-0.5 text-xs text-ink-soft tabular-nums">
                  Signs in with {a.phone}
                  {a.lastLoginAt ? ` · last seen ${formatDateTime(a.lastLoginAt)}` : a.activated ? " · has not signed in since" : ""}
                </p>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  <Button
                    size="sm"
                    variant="secondary"
                    loading={busy === `reset${a.accountId}`}
                    onClick={() => {
                      if (a.activated && !confirm("This signs them out and their current password stops working. Issue a new code?")) return;
                      run(`reset${a.accountId}`, () => api<PortalGranted>(`${base}/${a.accountId}/reset`, { method: "POST" }));
                    }}
                  >
                    {a.activated ? "Forgot password: new code" : "New code"}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    loading={busy === `toggle${a.accountId}`}
                    onClick={() => run(`toggle${a.accountId}`, () => api<PortalAccess>(`${base}/${a.accountId}/${a.active ? "disable" : "enable"}`, { method: "POST" }))}
                  >
                    {a.active ? "Switch off" : "Switch on"}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap gap-2">
          {!has("STUDENT") && (
            <Button loading={busy === "STUDENT"} onClick={() => run("STUDENT", () => api<PortalGranted>(base, { body: { relation: "STUDENT" } }))}>
              Give student access
            </Button>
          )}
          {!has("PARENT") && (
            <Button
              variant="secondary"
              loading={busy === "PARENT"}
              disabled={!student.parentPhone || student.parentPhone === student.phone}
              title={!student.parentPhone ? "Add the parent's phone number to the profile first" : student.parentPhone === student.phone ? "The parent uses the same phone number as the student" : undefined}
              onClick={() => run("PARENT", () => api<PortalGranted>(base, { body: { relation: "PARENT" } }))}
            >
              Give parent access
            </Button>
          )}
        </div>
        {!student.parentPhone && <p className="text-xs text-ink-faint">To give the parent their own login, add the parent&apos;s phone number to the profile.</p>}
      </div>

      <Modal open={issued !== null} onClose={() => setIssued(null)} title="Portal access">
        {issued &&
          (issued.activationCode ? (
            <div className="space-y-4">
              <Alert tone="amber">This code is shown only once. Give it to {issued.access.displayName} by phone or WhatsApp now.</Alert>
              <dl className="space-y-3 rounded-lg bg-muted p-4 text-sm">
                <div>
                  <dt className="text-xs text-ink-faint">1. Open</dt>
                  <dd className="font-medium break-all">{portalUrl}</dd>
                </div>
                <div>
                  <dt className="text-xs text-ink-faint">2. Choose “First time here” and enter this phone number</dt>
                  <dd className="font-medium tabular-nums">{issued.access.phone}</dd>
                </div>
                <div>
                  <dt className="text-xs text-ink-faint">3. One-time code (valid 7 days)</dt>
                  <dd className="font-mono text-2xl font-semibold tracking-[0.2em]">{issued.activationCode}</dd>
                </div>
              </dl>
              <p className="text-xs text-ink-soft">They will choose their own password. If the code is lost or expires, use “New code”.</p>
              <Button onClick={() => setIssued(null)}>Done</Button>
            </div>
          ) : (
            <div className="space-y-4">
              <Alert tone="green">
                {issued.access.displayName} already has a working portal login on {issued.access.phone}. This student now appears in it; no new code is needed.
              </Alert>
              <Button onClick={() => setIssued(null)}>Done</Button>
            </div>
          ))}
      </Modal>
    </Card>
  );
}

function AlumniCard({ student }: { student: Student }) {
  const { data, error, loading, setData } = useApi<{ alumni: AlumniDetail | null }>(`/api/students/${student.id}/alumni`);
  const [adding, setAdding] = useState(false);
  const a = data?.alumni;

  return (
    <Card title="Alumni record">
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : a ? (
        <div className="space-y-3 text-sm">
          <p>
            Admitted to <b>{a.alumni.collegeName}</b> ({a.alumni.course}, {a.alumni.admissionYear}).
          </p>
          <ul className="space-y-1 text-ink-soft">
            <li>{a.alumni.latestRating === null ? "Not surveyed yet" : `Latest satisfaction rating: ${a.alumni.latestRating} / 5`}</li>
            <li>
              {a.alumni.referrals} {a.alumni.referrals === 1 ? "referral" : "referrals"}
              {a.alumni.referralAdmissions > 0 && `, ${a.alumni.referralAdmissions} admitted`}
            </li>
            <li>
              {a.testimonials.length} {a.testimonials.length === 1 ? "testimonial" : "testimonials"}
            </li>
          </ul>
          <ButtonLink href={`/alumni/${a.alumni.id}`} variant="secondary">
            Open alumni record
          </ButtonLink>
        </div>
      ) : adding ? (
        <AlumniForm
          studentId={student.id}
          onDone={(d) => {
            setData({ alumni: d });
            setAdding(false);
          }}
        />
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-ink-soft">
            Once {student.fullName} has joined a college, add them to the{" "}
            <Link href="/alumni" className="text-brand-700 hover:underline">
              alumni directory
            </Link>{" "}
            to record their feedback, testimonials and the friends they refer. This happens automatically when the lead is marked “Admission confirmed” and a
            frozen seat is on record.
          </p>
          <Button variant="secondary" onClick={() => setAdding(true)}>
            Add to alumni directory
          </Button>
        </div>
      )}
    </Card>
  );
}
