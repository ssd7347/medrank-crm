"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatRupees } from "@/lib/format";
import type { Role, Student } from "@/lib/types";
import { LOAN_STATUSES, LOAN_STATUS_LABEL, type Loan, type LoanPartner, type LoanStatus, type StudentLoans } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, Card, EmptyState, Field, Input, Loading, Modal, Select, Textarea, type Tone } from "./ui";

export const LOAN_ROLES: Role[] = ["SUPER_ADMIN", "LOAN_DESK", "COUNSELLOR"];

export const LOAN_TONE: Record<LoanStatus, Tone> = {
  DRAFT: "gray",
  SUBMITTED: "blue",
  DOCS_PENDING: "amber",
  SANCTIONED: "green",
  DISBURSED: "green",
  REJECTED: "red",
  WITHDRAWN: "gray",
};

export function LoanDeadline({ loan }: { loan: Loan }) {
  if (!loan.neededBy) return <span className="text-ink-faint">No date set</span>;
  return (
    <span className="whitespace-nowrap">
      {formatDate(loan.neededBy)}
      {loan.risk === "LATE" && (
        <span className="ml-1.5">
          <Badge tone="red">Deadline passed</Badge>
        </span>
      )}
      {loan.risk === "AT_RISK" && (
        <span className="ml-1.5">
          <Badge tone="red">{loan.daysLeft === 0 ? "Due today" : `${loan.daysLeft} days left`}</Badge>
        </span>
      )}
    </span>
  );
}

export function LoansTab({ student }: { student: Student }) {
  const { data, error, loading, reload } = useApi<StudentLoans>(`/api/students/${student.id}/loans`);
  const partners = useApi<LoanPartner[]>("/api/loan-partners");
  const [editing, setEditing] = useState<Loan | "new" | null>(null);

  return (
    <div className="space-y-6">
      <Card title="Education loan applications" actions={<Button size="sm" onClick={() => setEditing("new")}>New application</Button>}>
        {error && <Alert>{error}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.applications.length ? (
          <EmptyState title="No loan application">
            If the family needs a loan, start it early: slow approval against the reporting deadline is a common reason seats are lost.
          </EmptyState>
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.applications.map((l) => (
              <li key={l.id} className="py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{l.partnerName}</span>
                  <Badge tone={LOAN_TONE[l.status]}>{LOAN_STATUS_LABEL[l.status]}</Badge>
                  <span className="text-sm tabular-nums">{formatRupees(l.amountRequested)}</span>
                  {l.amountSanctioned !== null && <span className="text-sm text-emerald-700 tabular-nums">sanctioned {formatRupees(l.amountSanctioned)}</span>}
                </div>
                <p className="mt-1 text-xs text-ink-soft">
                  Money needed by: <LoanDeadline loan={l} />
                  {l.appliedOn && ` · applied ${formatDate(l.appliedOn)}`}
                  {l.referenceNo && ` · ref ${l.referenceNo}`}
                  {l.handledBy && ` · handled by ${l.handledBy.fullName}`}
                </p>
                {l.notes && <p className="mt-1.5 text-sm whitespace-pre-wrap">{l.notes}</p>}
                <Button size="sm" variant="secondary" className="mt-2" onClick={() => setEditing(l)}>
                  Update
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="For the lender's form">
        <p className="mb-3 text-xs text-ink-soft">Details already on this student&apos;s profile, ready to copy into the lender&apos;s application.</p>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          {(
            [
              ["Student", student.fullName],
              ["Date of birth", student.dateOfBirth ? formatDate(student.dateOfBirth) : null],
              ["Phone", student.phone],
              ["Email", student.email],
              ["Parent / co-applicant", student.parentName],
              ["Parent phone", student.parentPhone],
              ["Category", student.category],
              ["Home state", student.homeState],
              ["NEET roll no.", student.neetRollNo],
              ["NEET score / AIR", student.neetScore === null ? null : `${student.neetScore} / ${student.neetAir ?? "—"}`],
            ] as [string, string | null][]
          ).map(([k, val]) => (
            <div key={k} className="flex justify-between gap-3 border-b border-line py-1">
              <dt className="text-ink-faint">{k}</dt>
              <dd className="text-right">{val ?? "—"}</dd>
            </div>
          ))}
        </dl>
      </Card>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "New loan application" : "Update loan application"}>
        {editing !== null && (
          <LoanForm
            studentId={student.id}
            loan={editing === "new" ? undefined : editing}
            partners={partners.data ?? []}
            suggestedNeededBy={data?.suggestedNeededBy ?? null}
            defaultCoApplicant={student.parentName}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </div>
  );
}

export function LoanForm({
  studentId,
  loan,
  partners,
  suggestedNeededBy,
  defaultCoApplicant,
  onDone,
}: {
  studentId: number;
  loan?: Loan;
  partners: LoanPartner[];
  suggestedNeededBy?: string | null;
  defaultCoApplicant?: string | null;
  onDone: () => void;
}) {
  const [v, setV] = useState({
    partnerId: loan?.partnerId.toString() ?? "",
    amountRequested: loan?.amountRequested.toString() ?? "",
    amountSanctioned: loan?.amountSanctioned?.toString() ?? "",
    status: (loan?.status ?? "DRAFT") as LoanStatus,
    neededBy: loan?.neededBy ?? suggestedNeededBy ?? "",
    appliedOn: loan?.appliedOn ?? "",
    referenceNo: loan?.referenceNo ?? "",
    coApplicant: loan?.coApplicant ?? defaultCoApplicant ?? "",
    notes: loan?.notes ?? "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const partner = partners.find((p) => String(p.id) === v.partnerId);
  const decided = v.status === "SANCTIONED" || v.status === "DISBURSED";

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const body = {
        ...v,
        partnerId: Number(v.partnerId),
        amountRequested: Number(v.amountRequested),
        amountSanctioned: v.amountSanctioned === "" ? null : Number(v.amountSanctioned),
        neededBy: v.neededBy || null,
        appliedOn: v.appliedOn || null,
      };
      if (loan) await api(`/api/loans/${loan.id}`, { method: "PUT", body });
      else await api(`/api/students/${studentId}/loans`, { body });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  if (!partners.length) {
    return <Alert tone="amber">No lenders have been added yet. The loan desk or an admin can add them on the Loan desk page.</Alert>;
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Lender" required hint={partner ? [partner.interestInfo && `Interest: ${partner.interestInfo}`, partner.maxAmount && `up to ${formatRupees(partner.maxAmount)}`].filter(Boolean).join(" · ") + " (for reference only)" : undefined}>
        {(id) => (
          <Select
            id={id}
            required
            value={v.partnerId}
            onChange={(e) => setV({ ...v, partnerId: e.target.value })}
            placeholder="Choose…"
            options={partners.filter((p) => p.active || String(p.id) === v.partnerId).map((p) => ({ value: String(p.id), label: p.name }))}
          />
        )}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Amount asked for (₹)" required>
          {(id) => <Input id={id} type="number" inputMode="numeric" required min={1} value={v.amountRequested} onChange={(e) => setV({ ...v, amountRequested: e.target.value })} />}
        </Field>
        <Field label="Money needed by" hint={suggestedNeededBy && !loan ? "Filled from the college reporting deadline" : "Usually the college reporting deadline"}>
          {(id) => <Input id={id} type="date" value={v.neededBy} onChange={(e) => setV({ ...v, neededBy: e.target.value })} />}
        </Field>
        <Field label="Status">
          {(id) => <Select id={id} value={v.status} onChange={(e) => setV({ ...v, status: e.target.value as LoanStatus })} options={LOAN_STATUSES} labelFor={(s) => LOAN_STATUS_LABEL[s as LoanStatus]} />}
        </Field>
        <Field label="Submitted to lender on" required={v.status !== "DRAFT"}>
          {(id) => <Input id={id} type="date" required={v.status !== "DRAFT"} value={v.appliedOn} onChange={(e) => setV({ ...v, appliedOn: e.target.value })} />}
        </Field>
        {decided && (
          <Field label="Amount sanctioned (₹)" required>
            {(id) => <Input id={id} type="number" inputMode="numeric" required min={1} value={v.amountSanctioned} onChange={(e) => setV({ ...v, amountSanctioned: e.target.value })} />}
          </Field>
        )}
        <Field label="Lender's reference no.">{(id) => <Input id={id} maxLength={60} value={v.referenceNo} onChange={(e) => setV({ ...v, referenceNo: e.target.value })} />}</Field>
        <Field label="Co-applicant">{(id) => <Input id={id} maxLength={120} value={v.coApplicant} onChange={(e) => setV({ ...v, coApplicant: e.target.value })} />}</Field>
      </div>
      <Field label="Notes">{(id) => <Textarea id={id} maxLength={2000} value={v.notes} onChange={(e) => setV({ ...v, notes: e.target.value })} />}</Field>
      <Button type="submit" loading={saving}>
        Save
      </Button>
    </form>
  );
}

export function PartnerForm({ partner, onDone }: { partner?: LoanPartner; onDone: () => void }) {
  const { hasRole } = useAuth();
  const [v, setV] = useState({
    name: partner?.name ?? "",
    interestInfo: partner?.interestInfo ?? "",
    maxAmount: partner?.maxAmount?.toString() ?? "",
    eligibility: partner?.eligibility ?? "",
    contactName: partner?.contactName ?? "",
    contactPhone: partner?.contactPhone ?? "",
    active: partner?.active ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!hasRole("SUPER_ADMIN", "LOAN_DESK")) return null;
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          const body = { ...v, maxAmount: v.maxAmount === "" ? null : Number(v.maxAmount) };
          if (partner) await api(`/api/loan-partners/${partner.id}`, { method: "PUT", body });
          else await api("/api/loan-partners", { body });
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
      <Field label="Lender name" required>
        {(id) => <Input id={id} required maxLength={120} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Interest rate (for reference)" hint="e.g. 10.5% to 12.5%">
          {(id) => <Input id={id} maxLength={200} value={v.interestInfo} onChange={(e) => setV({ ...v, interestInfo: e.target.value })} />}
        </Field>
        <Field label="Maximum loan (₹)">{(id) => <Input id={id} type="number" inputMode="numeric" min={0} value={v.maxAmount} onChange={(e) => setV({ ...v, maxAmount: e.target.value })} />}</Field>
        <Field label="Contact person">{(id) => <Input id={id} maxLength={120} value={v.contactName} onChange={(e) => setV({ ...v, contactName: e.target.value })} />}</Field>
        <Field label="Contact phone">{(id) => <Input id={id} type="tel" value={v.contactPhone} onChange={(e) => setV({ ...v, contactPhone: e.target.value })} />}</Field>
      </div>
      <Field label="Who is eligible" hint="Income, collateral, co-applicant or college conditions">
        {(id) => <Textarea id={id} maxLength={1000} value={v.eligibility} onChange={(e) => setV({ ...v, eligibility: e.target.value })} />}
      </Field>
      <label className="inline-flex items-center gap-2 text-sm">
        <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
        We currently refer students to this lender
      </label>
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
