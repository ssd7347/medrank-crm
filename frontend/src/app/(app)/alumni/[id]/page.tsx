"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { AlumniForm } from "@/components/alumni-form";
import { LeadStatusBadge } from "@/components/badges";
import { Alert, Badge, Button, ButtonLink, Card, Checkbox, DescList, EmptyState, Field, Loading, Modal, PageHeader, Select, Textarea, type Tone } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, label } from "@/lib/format";
import type { AlumniDetail, TestimonialStatus } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

const TESTIMONIAL_TONE: Record<TestimonialStatus, Tone> = { PENDING: "amber", APPROVED: "green", REJECTED: "gray" };
const RATINGS = ["5", "4", "3", "2", "1"];
const RATING_LABEL: Record<string, string> = { "5": "5 · Excellent", "4": "4 · Good", "3": "3 · Okay", "2": "2 · Poor", "1": "1 · Very poor" };

export default function AlumniDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const { data, error, loading, setData } = useApi<AlumniDetail>(`/api/alumni/${id}`);
  const [modal, setModal] = useState<"edit" | "survey" | "testimonial" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Alumni record not found"}</Alert>;
  const a = data.alumni;

  async function review(testimonialId: number, approve: boolean) {
    setActionError(null);
    try {
      setData(await api<AlumniDetail>(`/api/testimonials/${testimonialId}/${approve ? "approve" : "reject"}`, { method: "POST" }));
    } catch (e) {
      setActionError(errorMessage(e));
    }
  }

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/alumni" className="text-ink-soft hover:text-ink">
          ← Alumni
        </Link>
      </div>
      <PageHeader
        title={a.studentName}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <span>
              {a.collegeName} · {a.course} · {a.admissionYear}
            </span>
            {a.willingToRefer && <Badge tone="green">Will refer</Badge>}
          </span>
        }
        actions={
          <>
            <ButtonLink href={`/students/${a.studentId}`} variant="secondary">
              Student profile
            </ButtonLink>
            <Button variant="secondary" onClick={() => setModal("edit")}>
              Edit
            </Button>
          </>
        }
      />
      {actionError && (
        <div className="mb-4">
          <Alert>{actionError}</Alert>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card title="Satisfaction surveys" actions={<Button size="sm" onClick={() => setModal("survey")}>Record survey</Button>}>
            {!data.surveys.length ? (
              <EmptyState title="Not surveyed yet">Call the student or parent a few weeks after admission and record what they say.</EmptyState>
            ) : (
              <ul className="-my-2 divide-y divide-line">
                {data.surveys.map((s) => (
                  <li key={s.id} className="py-3">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-semibold tabular-nums">{s.overallRating} / 5 overall</span>
                      {s.counsellorRating !== null && <span className="text-ink-soft tabular-nums">· counsellor {s.counsellorRating} / 5</span>}
                      <Badge tone={s.wouldRecommend ? "green" : "red"}>{s.wouldRecommend ? "Would recommend" : "Would not recommend"}</Badge>
                    </div>
                    {s.comments && <p className="mt-1 text-sm whitespace-pre-wrap">{s.comments}</p>}
                    <p className="mt-1 text-xs text-ink-faint">
                      {formatDateTime(s.recordedAt)}
                      {s.recordedBy && ` · recorded by ${s.recordedBy.fullName}`}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="Testimonials" actions={<Button size="sm" onClick={() => setModal("testimonial")}>Add testimonial</Button>}>
            {!data.testimonials.length ? (
              <EmptyState title="No testimonials yet">Record what the family says in their own words. It can be used publicly only with their consent and an admin&apos;s approval.</EmptyState>
            ) : (
              <ul className="-my-2 divide-y divide-line">
                {data.testimonials.map((t) => (
                  <li key={t.id} className="py-3">
                    <blockquote className="border-l-2 border-line pl-3 text-sm whitespace-pre-wrap">{t.quote}</blockquote>
                    <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                      <Badge tone={TESTIMONIAL_TONE[t.status]}>{t.status === "APPROVED" ? "Approved for public use" : label(t.status)}</Badge>
                      <span className={t.consent ? "text-emerald-700" : "text-red-700"}>{t.consent ? "Family consented to publishing" : "No consent to publish"}</span>
                      <span className="text-ink-faint">{formatDate(t.createdAt)}</span>
                      {isAdmin && t.status === "PENDING" && (
                        <span className="flex gap-1.5">
                          <Button size="sm" variant="secondary" disabled={!t.consent} title={t.consent ? undefined : "Needs the family's consent first"} onClick={() => review(t.id, true)}>
                            Approve
                          </Button>
                          <Button size="sm" variant="ghost" onClick={() => review(t.id, false)}>
                            Reject
                          </Button>
                        </span>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="space-y-6">
          <Card title="Details">
            <DescList
              items={[
                ["Phone", a.phone],
                ["Quota", a.quota ? label(a.quota) : null],
                ["Counsellor", a.counsellor?.fullName],
                ["Branch", a.branch?.name],
              ]}
            />
            {data.notes && <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{data.notes}</p>}
          </Card>
          <Card title={`Referrals (${data.referrals.length})`} actions={<ButtonLink href="/leads/new">New lead</ButtonLink>}>
            {!data.referrals.length ? (
              <p className="text-sm text-ink-soft">
                None yet. When adding a lead, choose the source “Past-student referral” and pick {a.studentName} to credit them here.
              </p>
            ) : (
              <ul className="-my-2 divide-y divide-line">
                {data.referrals.map((r) => (
                  <li key={r.leadId} className="flex items-center justify-between gap-2 py-2.5 text-sm">
                    <span className="min-w-0">
                      <Link href={`/leads/${r.leadId}`} className="font-medium text-brand-800 hover:underline">
                        {r.leadName}
                      </Link>
                      <span className="block text-xs text-ink-faint">{formatDate(r.createdAt)}</span>
                    </span>
                    <LeadStatusBadge status={r.status} />
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>

      <Modal open={modal === "edit"} onClose={() => setModal(null)} title="Edit alumni record">
        {modal === "edit" && (
          <AlumniForm
            existing={data}
            onDone={(d) => {
              setData(d);
              setModal(null);
            }}
          />
        )}
      </Modal>
      <Modal open={modal === "survey"} onClose={() => setModal(null)} title="Record satisfaction survey">
        {modal === "survey" && (
          <SurveyForm
            alumniId={a.id}
            onDone={(d) => {
              setData(d);
              setModal(null);
            }}
          />
        )}
      </Modal>
      <Modal open={modal === "testimonial"} onClose={() => setModal(null)} title="Add testimonial">
        {modal === "testimonial" && (
          <TestimonialForm
            alumniId={a.id}
            onDone={(d) => {
              setData(d);
              setModal(null);
            }}
          />
        )}
      </Modal>
    </>
  );
}

function SurveyForm({ alumniId, onDone }: { alumniId: number; onDone: (d: AlumniDetail) => void }) {
  const [v, setV] = useState({ overallRating: "5", counsellorRating: "", wouldRecommend: true, comments: "" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          onDone(
            await api<AlumniDetail>(`/api/alumni/${alumniId}/surveys`, {
              body: { ...v, overallRating: Number(v.overallRating), counsellorRating: v.counsellorRating ? Number(v.counsellorRating) : null },
            }),
          );
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Overall experience" required>
          {(id) => <Select id={id} value={v.overallRating} onChange={(e) => setV({ ...v, overallRating: e.target.value })} options={RATINGS} labelFor={(r) => RATING_LABEL[r]} />}
        </Field>
        <Field label="Their counsellor">
          {(id) => (
            <Select id={id} value={v.counsellorRating} onChange={(e) => setV({ ...v, counsellorRating: e.target.value })} options={RATINGS} labelFor={(r) => RATING_LABEL[r]} placeholder="Not asked" />
          )}
        </Field>
      </div>
      <Checkbox label="Would recommend us to others" checked={v.wouldRecommend} onChange={(e) => setV({ ...v, wouldRecommend: e.target.checked })} />
      <Field label="What they said">{(id) => <Textarea id={id} maxLength={2000} value={v.comments} onChange={(e) => setV({ ...v, comments: e.target.value })} />}</Field>
      <Button type="submit" loading={saving}>
        Save survey
      </Button>
    </form>
  );
}

function TestimonialForm({ alumniId, onDone }: { alumniId: number; onDone: (d: AlumniDetail) => void }) {
  const [quote, setQuote] = useState("");
  const [consent, setConsent] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          onDone(await api<AlumniDetail>(`/api/alumni/${alumniId}/testimonials`, { body: { quote, consent } }));
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="In their words" required>
        {(id) => <Textarea id={id} required rows={4} maxLength={2000} value={quote} onChange={(e) => setQuote(e.target.value)} />}
      </Field>
      <Checkbox label="The family agreed that this may be published with the student's name" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
      <p className="text-xs text-ink-soft">Without consent it is kept for internal reference only and cannot be approved for public use.</p>
      <Button type="submit" loading={saving}>
        Save testimonial
      </Button>
    </form>
  );
}
