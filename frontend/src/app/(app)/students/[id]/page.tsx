"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { CounsellingTab } from "@/components/counselling-tab";
import { DocumentsTab } from "@/components/documents-tab";
import { FeesTab } from "@/components/fees-tab";
import { StudentTickets } from "@/components/tickets";
import { StudentForm } from "@/components/student-form";
import { AgreementsTab } from "@/components/agreements-tab";
import { CallButton } from "@/components/call-button";
import { LOAN_ROLES, LoansTab } from "@/components/loans";
import { PortalAlumniTab } from "@/components/portal-alumni-tab";
import { RiskBadge } from "@/components/score-badge";
import { SessionsTab } from "@/components/sessions-tab";
import { MessagesTab, ShortlistTab } from "@/components/student-tabs";
import { Alert, Badge, Button, ButtonLink, Card, DescList, Loading, PageHeader, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { DOCUMENT_ROLES, FEE_READ_ROLES, useAuth } from "@/lib/auth";
import { formatDate, formatNumber, label } from "@/lib/format";
import type { Student, StudentRequest } from "@/lib/types";
import { useApi } from "@/lib/use-api";

function toRequest(s: Student): StudentRequest {
  return {
    fullName: s.fullName,
    dateOfBirth: s.dateOfBirth,
    gender: s.gender,
    phone: s.phone,
    email: s.email,
    parentName: s.parentName,
    parentPhone: s.parentPhone,
    category: s.category,
    pwd: s.pwd,
    homeState: s.homeState,
    domicileStatus: s.domicileStatus,
    nationality: s.nationality,
    nriSponsored: s.nriSponsored,
    neetYear: s.neetYear,
    neetRollNo: s.neetRollNo,
    neetQualified: s.neetQualified,
    neetScore: s.neetScore,
    neetPercentile: s.neetPercentile,
    neetAir: s.neetAir,
    categoryRank: s.categoryRank,
    categoryCertValidUntil: s.categoryCertValidUntil,
    languagePreference: s.languagePreference,
    apaarId: s.apaarId,
    assignedCounsellorId: s.assignedCounsellor?.id ?? null,
    branchId: s.branch?.id ?? null,
  };
}

const TABS = [
  { key: "profile", label: "Profile" },
  { key: "counselling", label: "Counselling" },
  { key: "documents", label: "Documents" },
  { key: "fees", label: "Fees" },
  { key: "loans", label: "Loans" },
  { key: "sessions", label: "Sessions & calls" },
  { key: "agreements", label: "Agreements" },
  { key: "shortlist", label: "Shortlist" },
  { key: "tickets", label: "Tickets" },
  { key: "messages", label: "Messages" },
  { key: "portal", label: "Portal & alumni" },
] as const;

export default function StudentPage() {
  return (
    <Suspense fallback={<Loading />}>
      <StudentView />
    </Suspense>
  );
}

function StudentView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const params = useSearchParams();
  const tab = TABS.some((t) => t.key === params.get("tab")) ? params.get("tab")! : "profile";
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data: s, error, loading, setData } = useApi<Student>(`/api/students/${id}`);
  const [editing, setEditing] = useState(false);
  const [callsVersion, setCallsVersion] = useState(0);

  if (loading && !s) return <Loading />;
  if (error || !s) return <Alert>{error ?? "Student not found"}</Alert>;

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/students" className="text-ink-soft hover:text-ink">
          ← Students
        </Link>
      </div>
      <PageHeader
        title={s.fullName}
        subtitle={
          <span className="flex flex-wrap gap-2">
            <Badge tone="indigo">{s.category}</Badge>
            {s.pwd && <Badge tone="amber">PwD</Badge>}
            <span>{s.homeState}</span>
            <span>· {label(s.nationality)}</span>
            <RiskBadge studentId={s.id} version={callsVersion} />
            {s.leadId && (
              <Link href={`/leads/${s.leadId}`} className="text-brand-700 hover:underline">
                · view lead history
              </Link>
            )}
          </span>
        }
        actions={
          !editing && (
            <>
              <CallButton target={{ studentId: s.id }} phone={s.phone} name={s.fullName} onLogged={() => setCallsVersion((n) => n + 1)} />
              <ButtonLink href={`/predictor?studentId=${id}`} variant="secondary">
                Predict colleges
              </ButtonLink>
              {canEdit && tab === "profile" && (
                <Button variant="secondary" onClick={() => setEditing(true)}>
                  Edit profile
                </Button>
              )}
            </>
          )
        }
      />

      {!editing && (
        <div role="tablist" className="-mx-4 mb-5 flex gap-1 overflow-x-auto border-b border-line px-4 sm:mx-0 sm:px-0">
          {TABS.filter((t) => (t.key !== "documents" || hasRole(...DOCUMENT_ROLES)) && (t.key !== "fees" || hasRole(...FEE_READ_ROLES)) && (t.key !== "portal" || canEdit) && (t.key !== "loans" || hasRole(...LOAN_ROLES))).map((t) => (
            <button
              key={t.key}
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => router.replace(`/students/${id}?tab=${t.key}`, { scroll: false })}
              className={cx(
                "-mb-px border-b-2 px-3 py-2 text-sm whitespace-nowrap",
                tab === t.key ? "border-brand-600 font-medium text-brand-800" : "border-transparent text-ink-soft hover:text-ink",
              )}
            >
              {t.label}
            </button>
          ))}
        </div>
      )}

      {tab === "counselling" && !editing ? (
        <CounsellingTab studentId={s.id} studentCategory={s.category} />
      ) : tab === "shortlist" && !editing ? (
        <ShortlistTab studentId={s.id} />
      ) : tab === "documents" && !editing ? (
        <DocumentsTab studentId={s.id} />
      ) : tab === "fees" && !editing ? (
        <FeesTab studentId={s.id} />
      ) : tab === "loans" && !editing && hasRole(...LOAN_ROLES) ? (
        <LoansTab student={s} />
      ) : tab === "sessions" && !editing ? (
        <SessionsTab studentId={s.id} callsVersion={callsVersion} />
      ) : tab === "agreements" && !editing ? (
        <AgreementsTab studentId={s.id} />
      ) : tab === "tickets" && !editing ? (
        <StudentTickets studentId={s.id} />
      ) : tab === "messages" && !editing ? (
        <MessagesTab studentId={s.id} />
      ) : tab === "portal" && !editing && canEdit ? (
        <PortalAlumniTab student={s} />
      ) : editing ? (
        <Card title="Edit profile">
          <StudentForm
            initial={toRequest(s)}
            submitLabel="Save changes"
            onCancel={() => setEditing(false)}
            onSubmit={async (req) => {
              setData(await api<Student>(`/api/students/${id}`, { method: "PUT", body: req }));
              setEditing(false);
            }}
          />
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            <Card title="NEET result">
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                <Big label="Score" value={s.neetScore === null ? "—" : `${s.neetScore}/720`} />
                <Big label="All India Rank" value={formatNumber(s.neetAir)} />
                <Big label="Category rank" value={formatNumber(s.categoryRank)} />
                <Big label="Percentile" value={s.neetPercentile?.toString() ?? "—"} />
              </div>
              <div className="mt-4">
                <DescList
                  items={[
                    ["NEET year", s.neetYear],
                    ["Roll number", s.neetRollNo],
                    ["Qualified", s.neetQualified ? "Yes" : "No"],
                  ]}
                />
              </div>
            </Card>
            <Card title="Profile">
              <DescList
                items={[
                  ["Phone", s.phone],
                  ["Email", s.email],
                  ["Date of birth", s.dateOfBirth ? formatDate(s.dateOfBirth) : null],
                  ["Gender", s.gender ? label(s.gender) : null],
                  ["Parent / guardian", s.parentName],
                  ["Parent phone", s.parentPhone],
                  ["Domicile", label(s.domicileStatus)],
                  ["Category certificate valid until", s.categoryCertValidUntil ? formatDate(s.categoryCertValidUntil) : null],
                  ["NRI sponsored", s.nriSponsored ? "Yes" : "No"],
                  ["Preferred language", label(s.languagePreference)],
                  ["APAAR ID", s.apaarId],
                  ["Counsellor", s.assignedCounsellor?.fullName],
                  ["Branch", s.branch?.name],
                ]}
              />
            </Card>
          </div>

          <Card title="Quota eligibility">
            {s.eligibility.warnings.length > 0 && (
              <div className="mb-3 space-y-2">
                {s.eligibility.warnings.map((w) => (
                  <Alert key={w} tone="amber">
                    {w}
                  </Alert>
                ))}
              </div>
            )}
            <ul className="divide-y divide-line">
              {s.eligibility.quotas.map((q) => (
                <li key={q.quota} className="flex items-start gap-3 py-2.5">
                  <span
                    className={`mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full text-xs font-bold ${q.eligible ? "bg-emerald-100 text-emerald-700" : "bg-slate-100 text-slate-500"}`}
                    aria-label={q.eligible ? "Eligible" : "Not eligible"}
                  >
                    {q.eligible ? "✓" : "–"}
                  </span>
                  <div>
                    <p className="text-sm font-medium">{label(q.quota)}</p>
                    <p className="text-xs text-ink-soft">{q.reason}</p>
                  </div>
                </li>
              ))}
            </ul>
            <p className="mt-3 border-t border-line pt-3 text-xs text-ink-faint">{s.eligibility.disclaimer}</p>
          </Card>
        </div>
      )}
    </>
  );
}

function Big({ label: l, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-muted p-3">
      <p className="text-xs text-ink-faint">{l}</p>
      <p className="mt-0.5 text-lg font-semibold tabular-nums">{value}</p>
    </div>
  );
}
