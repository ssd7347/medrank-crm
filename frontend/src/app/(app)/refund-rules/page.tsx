"use client";

import { useState } from "react";

import { CollegePicker } from "@/components/college-picker";
import { outcomeMessage, submitChange } from "@/components/master-data-forms";
import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { DATA_ROLES, useAuth } from "@/lib/auth";
import { formatRupees, label } from "@/lib/format";
import { ROUNDS, type College } from "@/lib/types";
import type { Authority } from "@/lib/types-counselling";
import type { RefundRuleView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

const thisYear = new Date().getFullYear();

/** Seat withdrawal & refund rules (spec 4.27). Shown to counsellors before any withdraw decision. */
export default function RefundRulesPage() {
  const { hasRole } = useAuth();
  const canPropose = hasRole(...DATA_ROLES);
  const [year, setYear] = useState(String(thisYear));
  const rules = useApi<RefundRuleView[]>("/api/refund-rules", { year });
  const authorities = useApi<Authority[]>("/api/counselling/authorities");
  const [editing, setEditing] = useState<RefundRuleView | "new" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const authName = (id: number | null) => (id ? (authorities.data?.find((a) => a.id === id)?.code ?? `#${id}`) : "Any");

  return (
    <>
      <PageHeader
        title="Withdrawal & refund rules"
        subtitle="What a student loses by giving up an allotted seat. Enter these from each year's official notification; changes need admin approval."
        actions={
          <>
            <Select aria-label="Year" value={year} onChange={(e) => setYear(e.target.value)} options={[thisYear + 1, thisYear, thisYear - 1].map(String)} className="w-28" />
            {canPropose && <Button onClick={() => setEditing("new")}>Add rule</Button>}
          </>
        }
      />
      {notice && (
        <div className="mb-4">
          <Alert tone="green">{notice}</Alert>
        </div>
      )}
      {rules.error && <Alert>{rules.error}</Alert>}
      <Card>
        <div className="-m-4">
          {rules.loading && !rules.data ? (
            <Loading />
          ) : !rules.data?.length ? (
            <EmptyState title={`No ${year} rules yet`}>Until rules are added, counsellors see only general warnings before a withdrawal.</EmptyState>
          ) : (
            <Table head={["Applies to", "Round", "Time limit", "Security deposit", "Tuition refund", "Later rounds", "Source", ""]}>
              {rules.data.map((r) => (
                <tr key={r.id}>
                  <Td>
                    {authName(r.authorityId)}
                    {r.collegeId && <span className="block text-xs text-ink-soft">College #{r.collegeId}</span>}
                  </Td>
                  <Td>{r.roundType ? label(r.roundType) : "Any"}</Td>
                  <Td>{r.maxDaysAfterAllotment === null ? "Any time" : `Up to day ${r.maxDaysAfterAllotment}`}</Td>
                  <Td>{r.depositForfeited ? <Badge tone="red">Forfeited{r.depositAmount ? ` ${formatRupees(r.depositAmount)}` : ""}</Badge> : <Badge tone="green">Refunded</Badge>}</Td>
                  <Td className="tabular-nums">{r.tuitionRefundPercent}%</Td>
                  <Td>{r.barredFromLaterRounds ? <Badge tone="red">Barred</Badge> : "Allowed"}</Td>
                  <Td className="max-w-xs text-xs text-ink-soft">{r.source}</Td>
                  <Td className="text-right">
                    {canPropose && (
                      <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>
                        Edit
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add refund rule" : "Edit refund rule"} wide>
        {editing !== null && (
          <RuleForm
            rule={editing === "new" ? undefined : editing}
            year={Number(year)}
            authorities={authorities.data ?? []}
            onDone={(msg) => {
              setEditing(null);
              setNotice(msg);
              rules.reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function RuleForm({ rule, year, authorities, onDone }: { rule?: RefundRuleView; year: number; authorities: Authority[]; onDone: (msg: string) => void }) {
  const [v, setV] = useState({
    authorityId: rule?.authorityId ? String(rule.authorityId) : "",
    roundType: rule?.roundType ?? "",
    maxDays: rule?.maxDaysAfterAllotment?.toString() ?? "",
    depositForfeited: rule?.depositForfeited ?? false,
    depositAmount: rule?.depositAmount?.toString() ?? "",
    tuitionRefundPercent: rule?.tuitionRefundPercent?.toString() ?? "100",
    barred: rule?.barredFromLaterRounds ?? false,
    source: rule?.source ?? "",
    notes: rule?.notes ?? "",
  });
  const [college, setCollege] = useState<College | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          const r = await submitChange("REFUND_RULE", rule ? "UPDATE" : "CREATE", rule?.id ?? null, {
            authorityId: v.authorityId ? Number(v.authorityId) : null,
            collegeId: college?.id ?? rule?.collegeId ?? null,
            roundType: v.roundType || null,
            academicYear: rule?.academicYear ?? year,
            maxDaysAfterAllotment: v.maxDays ? Number(v.maxDays) : null,
            depositForfeited: v.depositForfeited,
            depositAmount: v.depositAmount ? Number(v.depositAmount) : null,
            tuitionRefundPercent: Number(v.tuitionRefundPercent),
            barredFromLaterRounds: v.barred,
            source: v.source,
            notes: v.notes || null,
          });
          onDone(outcomeMessage(r));
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Authority">
          {(id) => <Select id={id} value={v.authorityId} onChange={(e) => setV({ ...v, authorityId: e.target.value })} placeholder="Any (college rule)" options={authorities.map((a) => ({ value: String(a.id), label: a.code }))} />}
        </Field>
        <Field label="Round">{(id) => <Select id={id} value={v.roundType} onChange={(e) => setV({ ...v, roundType: e.target.value })} placeholder="Any round" options={ROUNDS} labelFor={label} />}</Field>
        <Field label="Only up to day (after allotment)" hint="Blank = any time; add several rows for tiers">
          {(id) => <Input id={id} type="number" min={0} value={v.maxDays} onChange={(e) => setV({ ...v, maxDays: e.target.value })} />}
        </Field>
      </div>
      <Field label="Specific college (optional)" hint={rule?.collegeId && !college ? `Currently college #${rule.collegeId}` : "Leave empty for an authority-wide rule"}>
        {() => <CollegePicker value={college} onChange={setCollege} />}
      </Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <div className="flex items-end pb-2">
          <Checkbox label="Security deposit forfeited" checked={v.depositForfeited} onChange={(e) => setV({ ...v, depositForfeited: e.target.checked })} />
        </div>
        <Field label="Deposit amount (₹)">{(id) => <Input id={id} type="number" min={0} value={v.depositAmount} onChange={(e) => setV({ ...v, depositAmount: e.target.value })} />}</Field>
        <Field label="Tuition refunded (%)" required>
          {(id) => <Input id={id} type="number" required min={0} max={100} value={v.tuitionRefundPercent} onChange={(e) => setV({ ...v, tuitionRefundPercent: e.target.value })} />}
        </Field>
      </div>
      <Checkbox label="Barred from later rounds of this counselling" checked={v.barred} onChange={(e) => setV({ ...v, barred: e.target.checked })} />
      <Field label="Source" required hint="e.g. MCC information bulletin 2026, clause 8.4">
        {(id) => <Input id={id} required maxLength={300} value={v.source} onChange={(e) => setV({ ...v, source: e.target.value })} />}
      </Field>
      <Field label="Notes">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={v.notes} onChange={(e) => setV({ ...v, notes: e.target.value })} />}</Field>
      <Button type="submit" loading={saving}>
        {rule ? "Submit change" : "Add rule"}
      </Button>
    </form>
  );
}
