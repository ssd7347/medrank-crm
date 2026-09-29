"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { StudentForm } from "@/components/student-form";
import { Alert, Badge, Button, Card, DescList, Loading, PageHeader } from "@/components/ui";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
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
  };
}

export default function StudentPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data: s, error, loading, setData } = useApi<Student>(`/api/students/${id}`);
  const [editing, setEditing] = useState(false);

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
            {s.leadId && (
              <Link href={`/leads/${s.leadId}`} className="text-brand-700 hover:underline">
                · view lead history
              </Link>
            )}
          </span>
        }
        actions={canEdit && !editing && <Button variant="secondary" onClick={() => setEditing(true)}>Edit profile</Button>}
      />

      {editing ? (
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
