"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, PageHeader, Select, cx, type Tone } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { INDIAN_STATES, formatNumber, formatRupees, label } from "@/lib/format";
import { CATEGORIES, COLLEGE_TYPES, COURSES, type Student } from "@/lib/types";
import type { Band, PredictResponse, Prediction } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

const BAND_TONE: Record<Band, Tone> = { HIGH: "green", MODERATE: "amber", LOW: "red" };
const PREDICTOR_QUOTAS = ["AIQ", "STATE", "DEEMED", "MANAGEMENT", "NRI"] as const;

function PredictorView() {
  const params = useSearchParams();
  const studentId = params.get("studentId");
  const { hasRole } = useAuth();
  const canShortlist = !!studentId && hasRole("SUPER_ADMIN", "COUNSELLOR");
  const student = useApi<Student>(studentId ? `/api/students/${studentId}` : null);

  const [f, setF] = useState({
    rank: "",
    category: "GEN",
    pwd: false,
    homeState: "Tamil Nadu",
    domiciled: true,
    course: "MBBS",
    quotas: ["AIQ", "STATE", "DEEMED", "MANAGEMENT"] as string[],
    state: "",
    collegeType: "",
    maxFee: "",
  });
  const [result, setResult] = useState<PredictResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<Set<string>>(new Set());

  async function predict(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const common = {
        course: f.course,
        states: f.state ? [f.state] : null,
        collegeTypes: f.collegeType ? [f.collegeType] : null,
        maxAnnualFee: f.maxFee ? Number(f.maxFee) : null,
      };
      const body = studentId
        ? { ...common, studentId: Number(studentId), rank: f.rank ? Number(f.rank) : null }
        : {
            ...common,
            rank: Number(f.rank),
            category: f.category,
            pwd: f.pwd,
            homeState: f.homeState || null,
            domiciled: f.domiciled,
            quotas: f.quotas,
          };
      setResult(await api<PredictResponse>("/api/predictor", { body }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function shortlist(p: Prediction) {
    const key = `${p.collegeId}|${p.quota}`;
    try {
      await api(`/api/students/${studentId}/shortlist`, {
        body: { collegeId: p.collegeId, course: p.course, quota: p.quota, band: p.band },
      });
      setSaved(new Set(saved).add(key));
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setSaved(new Set(saved).add(key));
      else setError(errorMessage(e));
    }
  }

  const toggleQuota = (q: string) =>
    setF({ ...f, quotas: f.quotas.includes(q) ? f.quotas.filter((x) => x !== q) : [...f.quotas, q] });

  const bands: Band[] = ["HIGH", "MODERATE", "LOW"];

  return (
    <>
      <PageHeader
        title="College predictor"
        subtitle={
          student.data ? (
            <>
              For{" "}
              <Link href={`/students/${studentId}`} className="text-brand-700 hover:underline">
                {student.data.fullName}
              </Link>{" "}
              · {student.data.category} · {student.data.homeState} · AIR {formatNumber(student.data.neetAir)}
            </>
          ) : (
            "Compare a rank with previous years' closing ranks."
          )
        }
      />

      <div className="grid gap-6 lg:grid-cols-[20rem_1fr]">
        <Card title="Inputs" className="h-fit lg:sticky lg:top-6">
          <form onSubmit={predict} className="space-y-4">
            <Field label={studentId ? "Rank (leave empty to use the student's AIR)" : "Rank"} required={!studentId}>
              {(id) => <Input id={id} type="number" inputMode="numeric" min={1} required={!studentId} value={f.rank} onChange={(e) => setF({ ...f, rank: e.target.value })} />}
            </Field>
            {!studentId && (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Category">{(id) => <Select id={id} value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} options={CATEGORIES} />}</Field>
                  <div className="flex items-end pb-2">
                    <Checkbox label="PwD" checked={f.pwd} onChange={(e) => setF({ ...f, pwd: e.target.checked })} />
                  </div>
                </div>
                <Field label="Home state">
                  {(id) => <Select id={id} value={f.homeState} onChange={(e) => setF({ ...f, homeState: e.target.value })} options={INDIAN_STATES} placeholder="—" />}
                </Field>
                <Checkbox label="Domiciled in home state" checked={f.domiciled} onChange={(e) => setF({ ...f, domiciled: e.target.checked })} />
                <fieldset>
                  <legend className="mb-1 text-xs font-medium text-ink-soft">Quotas</legend>
                  <div className="flex flex-wrap gap-2">
                    {PREDICTOR_QUOTAS.map((q) => (
                      <button
                        type="button"
                        key={q}
                        onClick={() => toggleQuota(q)}
                        aria-pressed={f.quotas.includes(q)}
                        className={cx("rounded-full border px-3 py-1 text-xs", f.quotas.includes(q) ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface")}
                      >
                        {q}
                      </button>
                    ))}
                  </div>
                </fieldset>
              </>
            )}
            <div className="grid grid-cols-2 gap-3">
              <Field label="Course">{(id) => <Select id={id} value={f.course} onChange={(e) => setF({ ...f, course: e.target.value })} options={COURSES} />}</Field>
              <Field label="Max fee / year (₹)">
                {(id) => <Input id={id} type="number" inputMode="numeric" min={0} value={f.maxFee} onChange={(e) => setF({ ...f, maxFee: e.target.value })} />}
              </Field>
            </div>
            <Field label="Only colleges in">
              {(id) => <Select id={id} value={f.state} onChange={(e) => setF({ ...f, state: e.target.value })} options={INDIAN_STATES} placeholder="Any state" />}
            </Field>
            <Field label="College type">
              {(id) => <Select id={id} value={f.collegeType} onChange={(e) => setF({ ...f, collegeType: e.target.value })} options={COLLEGE_TYPES} labelFor={label} placeholder="Any type" />}
            </Field>
            <Button type="submit" loading={busy} className="w-full">
              Predict
            </Button>
          </form>
        </Card>

        <div className="min-w-0 space-y-4">
          {error && <Alert>{error}</Alert>}
          {!result ? (
            <Card>
              <EmptyState title="Enter a rank to see likely colleges">Results are grouped into high, moderate and low chance.</EmptyState>
            </Card>
          ) : (
            <>
              <Alert tone="amber">{result.disclaimer}</Alert>
              <p className="text-sm text-ink-soft">
                Rank {formatNumber(result.rank)} · {result.category}
                {result.pwd && " PwD"} · {result.course} · quotas {result.quotas.join(", ") || "none"} · data years{" "}
                {result.dataYears.join(", ") || "none"}
              </p>
              {result.results.length === 0 ? (
                <Card>
                  <EmptyState title="No matching colleges">Either the rank is beyond recent closing ranks, or closing-rank data has not been entered for these quotas yet.</EmptyState>
                </Card>
              ) : (
                bands.map((band) => {
                  const rows = result.results.filter((r) => r.band === band);
                  if (!rows.length) return null;
                  return (
                    <section key={band}>
                      <h2 className="mb-2 flex items-center gap-2 text-sm font-semibold">
                        <Badge tone={BAND_TONE[band]}>{label(band)} chance</Badge>
                        <span className="text-ink-faint">{rows.length}</span>
                      </h2>
                      <div className="grid gap-3 xl:grid-cols-2">
                        {rows.map((p) => {
                          const key = `${p.collegeId}|${p.quota}`;
                          return (
                            <article key={key} className="rounded-xl border border-line bg-surface p-4 shadow-sm">
                              <div className="flex items-start justify-between gap-3">
                                <div className="min-w-0">
                                  <Link href={`/colleges/${p.collegeId}`} className="font-medium text-brand-800 hover:underline">
                                    {p.collegeName}
                                  </Link>
                                  <p className="text-xs text-ink-soft">
                                    {p.city ? `${p.city}, ` : ""}
                                    {p.state} · {label(p.collegeType)}
                                  </p>
                                </div>
                                <Badge tone="indigo">{p.quota}</Badge>
                              </div>
                              <dl className="mt-3 grid grid-cols-2 gap-2 text-sm">
                                <div>
                                  <dt className="text-xs text-ink-faint">Last closing rank ({p.latestYear})</dt>
                                  <dd className="font-semibold tabular-nums">{formatNumber(p.lastClosingRank)}</dd>
                                </div>
                                <div>
                                  <dt className="text-xs text-ink-faint">Tuition / year{p.feeYear ? ` (${p.feeYear})` : ""}</dt>
                                  <dd className="tabular-nums">{formatRupees(p.annualTuition)}</dd>
                                </div>
                              </dl>
                              <details className="mt-2 text-xs text-ink-soft">
                                <summary className="cursor-pointer">Closing ranks by round</summary>
                                <ul className="mt-1 space-y-0.5">
                                  {p.history.map((h) => (
                                    <li key={`${h.year}${h.round}`} className="flex justify-between tabular-nums">
                                      <span>
                                        {h.year} {label(h.round)}
                                      </span>
                                      <span>{formatNumber(h.closingRank)}</span>
                                    </li>
                                  ))}
                                </ul>
                              </details>
                              {canShortlist && (
                                <Button size="sm" variant="secondary" className="mt-3" disabled={saved.has(key)} onClick={() => shortlist(p)}>
                                  {saved.has(key) ? "On shortlist ✓" : "Add to shortlist"}
                                </Button>
                              )}
                            </article>
                          );
                        })}
                      </div>
                    </section>
                  );
                })
              )}
              {result.truncated && <p className="text-xs text-ink-faint">Showing the first 300 matches; narrow the filters to see others.</p>}
            </>
          )}
        </div>
      </div>
    </>
  );
}

export default function PredictorPage() {
  return (
    <Suspense fallback={<Loading />}>
      <PredictorView />
    </Suspense>
  );
}
