"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { Alert, Badge, Card, EmptyState, Input, Loading, PageHeader, Table, Td } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { label } from "@/lib/format";
import type { AlumniDirectory } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

export default function AlumniPage() {
  const { hasRole } = useAuth();
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const t = setTimeout(() => setQ(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);
  const { data, error, loading } = useApi<AlumniDirectory>("/api/alumni", { q });
  const s = data?.summary;

  return (
    <>
      <PageHeader
        title="Alumni & referrals"
        subtitle="Students you placed: how satisfied they are, who they have referred, and what they are willing to say about you."
      />
      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {s && (
        <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <Stat label="Alumni" value={String(s.total)} hint={`${s.willingToRefer} happy to refer`} />
          <Stat label="Average rating" value={s.averageRating === null ? "—" : `${s.averageRating} / 5`} hint={`${s.surveyed} surveyed`} />
          <Stat label="Would recommend" value={s.recommendPercent === null ? "—" : `${s.recommendPercent}%`} hint="Of those surveyed" />
          <Stat label="Referrals from alumni" value={String(s.referrals)} hint={`${s.referralAdmissions} became admissions`} />
        </div>
      )}
      {hasRole("SUPER_ADMIN") && !!s?.testimonialsPending && (
        <div className="mb-4">
          <Alert tone="amber">
            {s.testimonialsPending} {s.testimonialsPending === 1 ? "testimonial is" : "testimonials are"} waiting for your approval. Open the alumni record to review.
          </Alert>
        </div>
      )}
      <Card className="overflow-hidden">
        <div className="-m-4">
          <div className="border-b border-line p-3 sm:max-w-sm">
            <Input placeholder="Search name, college or phone" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search alumni" />
          </div>
          {loading && !data ? (
            <Loading />
          ) : !data?.rows.length ? (
            <EmptyState title={q ? "No alumni match" : "No alumni yet"}>
              {q ? "Try a different search." : "Students are added here when their admission is confirmed, or from the student's “Portal & alumni” tab."}
            </EmptyState>
          ) : (
            <Table head={["Student", "College", "Year", "Rating", "Referrals", "Counsellor", ""]}>
              {data.rows.map((a) => (
                <tr key={a.id} className="hover:bg-muted/60">
                  <Td>
                    <Link href={`/alumni/${a.id}`} className="font-medium text-brand-800 hover:underline">
                      {a.studentName}
                    </Link>
                    <span className="block text-xs text-ink-soft tabular-nums">{a.phone}</span>
                  </Td>
                  <Td>
                    {a.collegeName}
                    <span className="block text-xs text-ink-soft">
                      {a.course}
                      {a.quota && ` · ${label(a.quota)}`}
                    </span>
                  </Td>
                  <Td className="tabular-nums">{a.admissionYear}</Td>
                  <Td className="whitespace-nowrap">
                    {a.latestRating === null ? (
                      <span className="text-ink-faint">Not surveyed</span>
                    ) : (
                      <span className="tabular-nums">
                        {a.latestRating} / 5{a.wouldRecommend === false && <span className="ml-1 text-xs text-red-700">would not recommend</span>}
                      </span>
                    )}
                  </Td>
                  <Td className="tabular-nums">
                    {a.referrals}
                    {a.referralAdmissions > 0 && <span className="ml-1 text-xs text-emerald-700">({a.referralAdmissions} admitted)</span>}
                  </Td>
                  <Td>
                    {a.counsellor?.fullName ?? "—"}
                    {a.branch && <span className="block text-xs text-ink-faint">{a.branch.name}</span>}
                  </Td>
                  <Td>{a.willingToRefer && <Badge tone="green">Will refer</Badge>}</Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
    </>
  );
}

function Stat({ label: l, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-4 shadow-sm">
      <p className="text-xs text-ink-faint">{l}</p>
      <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-soft">{hint}</p>}
    </div>
  );
}
