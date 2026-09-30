"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import type { Student } from "@/lib/types";
import type { AlumniDetail, PortalAccess, PortalRelation } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

import { AlumniForm } from "./alumni-form";
import { Alert, Badge, Button, ButtonLink, Card, EmptyState, Loading } from "./ui";

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
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function run(key: string, fn: () => Promise<PortalAccess>, done?: (a: PortalAccess) => string) {
    setBusy(key);
    setActionError(null);
    setNotice(null);
    try {
      const a = await fn();
      if (done) setNotice(done(a));
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  const has = (r: PortalRelation) => data?.some((a) => a.relation === r);
  const grant = (relation: PortalRelation) =>
    run(
      relation,
      () => api<PortalAccess>(base, { body: { relation } }),
      (a) => `${a.displayName} can now log in on the login page with ${a.phone} and a one-time code.`,
    );

  return (
    <Card title="Student & parent login">
      <div className="space-y-4">
        <p className="text-sm text-ink-soft">
          Lets the family see round status, deadlines, documents and fees on their own phone, and upload documents. They use the same login page as everyone
          else, with their mobile number and a one-time code.
        </p>
        {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
        {notice && <Alert tone="green">{notice}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No login yet">Give access below, or the student can create their own ID with Register on the login page.</EmptyState>
        ) : (
          <ul className="divide-y divide-line rounded-lg border border-line">
            {data.map((a) => (
              <li key={a.accountId} className="p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{a.displayName}</span>
                  <Badge tone="indigo">{label(a.relation)}</Badge>
                  {a.active ? <Badge tone="green">Can log in</Badge> : <Badge tone="red">Switched off</Badge>}
                  {a.selfRegistered && <Badge>Registered themselves</Badge>}
                </div>
                <p className="mt-0.5 text-xs text-ink-soft tabular-nums">
                  Logs in with {a.phone}
                  {a.lastLoginAt ? ` · last logged in ${formatDateTime(a.lastLoginAt)}` : " · has not logged in yet"}
                </p>
                <Button
                  size="sm"
                  variant="ghost"
                  className="mt-2"
                  loading={busy === `toggle${a.accountId}`}
                  onClick={() => run(`toggle${a.accountId}`, () => api<PortalAccess>(`${base}/${a.accountId}/${a.active ? "disable" : "enable"}`, { method: "POST" }))}
                >
                  {a.active ? "Switch off" : "Switch on"}
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap gap-2">
          {!has("STUDENT") && (
            <Button loading={busy === "STUDENT"} onClick={() => grant("STUDENT")}>
              Give student access
            </Button>
          )}
          {!has("PARENT") && (
            <Button
              variant="secondary"
              loading={busy === "PARENT"}
              disabled={!student.parentPhone || student.parentPhone === student.phone}
              title={!student.parentPhone ? "Add the parent's phone number to the profile first" : student.parentPhone === student.phone ? "The parent uses the same phone number as the student" : undefined}
              onClick={() => grant("PARENT")}
            >
              Give parent access
            </Button>
          )}
        </div>
        {!student.parentPhone && <p className="text-xs text-ink-faint">To give the parent their own login, add the parent&apos;s phone number to the profile.</p>}
      </div>
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
