"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { FEE_WRITE_ROLES, useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatRupees, label } from "@/lib/format";
import { PAYMENT_METHODS, type PackageView, type PlanView, type RefundView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, Card, EmptyState, Field, Input, Loading, Modal, Select, Table, Td, Textarea, cx, type Tone } from "./ui";

const INSTALLMENT_TONE: Record<string, Tone> = { PAID: "green", PARTIAL: "amber", DUE: "blue", OVERDUE: "red" };
const REFUND_TONE: Record<string, Tone> = { REQUESTED: "amber", APPROVED: "blue", REJECTED: "red", PAID: "green" };
const today = () => new Date().toISOString().slice(0, 10);

/** Consultancy fee plans, payments, receipts and refunds for one student (spec 4.8). */
export function FeesTab({ studentId }: { studentId: number }) {
  const { hasRole } = useAuth();
  const canWrite = hasRole(...FEE_WRITE_ROLES);
  const plans = useApi<PlanView[]>(`/api/students/${studentId}/fees`);
  const [creating, setCreating] = useState(false);

  if (plans.loading && !plans.data) return <Loading />;
  return (
    <div className="space-y-6">
      {plans.error && <Alert>{plans.error}</Alert>}
      {canWrite && (
        <div className="flex justify-end">
          <Button onClick={() => setCreating(true)}>New fee plan</Button>
        </div>
      )}
      {!plans.data?.length ? (
        <Card>
          <EmptyState title="No fee plan yet">{canWrite ? "Create a plan from a service package, then record payments against it." : "Accounts has not set up fees for this student."}</EmptyState>
        </Card>
      ) : (
        plans.data.map((p) => <PlanCard key={p.id} plan={p} canWrite={canWrite} onChanged={plans.reload} />)
      )}
      <Modal open={creating} onClose={() => setCreating(false)} title="New fee plan" wide>
        {creating && (
          <CreatePlan
            studentId={studentId}
            onDone={() => {
              setCreating(false);
              plans.reload();
            }}
          />
        )}
      </Modal>
    </div>
  );
}

function PlanCard({ plan, canWrite, onChanged }: { plan: PlanView; canWrite: boolean; onChanged: () => void }) {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const [paying, setPaying] = useState(false);
  const [refunding, setRefunding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      onChanged();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card
      title={
        <span className="flex flex-wrap items-center gap-2">
          {plan.name}
          <Badge tone={plan.status === "ACTIVE" ? "green" : "gray"}>{label(plan.status)}</Badge>
        </span>
      }
      actions={
        <>
          {canWrite && plan.status === "ACTIVE" && plan.balance > 0 && (
            <Button size="sm" onClick={() => setPaying(true)}>
              Record payment
            </Button>
          )}
          {plan.paid > 0 && (
            <Button size="sm" variant="secondary" onClick={() => setRefunding(true)}>
              Request refund
            </Button>
          )}
        </>
      }
    >
      {error && (
        <div className="mb-3">
          <Alert>{error}</Alert>
        </div>
      )}
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Money k="Total (after discount)" v={plan.netAmount} sub={plan.discount > 0 ? `${formatRupees(plan.discount)} discount` : undefined} />
        <Money k="Paid" v={plan.paid} />
        <Money k="Balance" v={plan.balance} strong={plan.balance > 0} />
        <Money k="Refunded" v={plan.refunded} />
      </dl>

      <h3 className="mt-5 mb-2 text-sm font-semibold">Instalments</h3>
      <div className="-mx-4">
        <Table head={["Instalment", "Due", "Amount", "Balance", "Status"]}>
          {plan.installments.map((i) => (
            <tr key={i.id}>
              <Td>{i.label}</Td>
              <Td className="whitespace-nowrap">{formatDate(i.dueDate)}</Td>
              <Td className="tabular-nums">{formatRupees(i.amount)}</Td>
              <Td className="tabular-nums">{formatRupees(i.balance)}</Td>
              <Td>
                <Badge tone={INSTALLMENT_TONE[i.state]}>{label(i.state)}</Badge>
              </Td>
            </tr>
          ))}
        </Table>
      </div>

      {plan.payments.length > 0 && (
        <>
          <h3 className="mt-5 mb-2 text-sm font-semibold">Payments</h3>
          <div className="-mx-4">
            <Table head={["Receipt", "Date", "Amount", "Method", ""]}>
              {plan.payments.map((p) => (
                <tr key={p.id} className={cx(p.voided && "opacity-60")}>
                  <Td>
                    <Link href={`/receipts/${p.id}`} className="font-mono text-xs text-brand-700 hover:underline">
                      {p.receiptNo}
                    </Link>
                    {p.voided && <span className="ml-2 text-xs text-red-700">Void: {p.voidReason}</span>}
                  </Td>
                  <Td className="whitespace-nowrap">{formatDate(p.paidOn)}</Td>
                  <Td className={cx("tabular-nums", p.voided && "line-through")}>{formatRupees(p.amount)}</Td>
                  <Td>
                    {label(p.method)}
                    {p.reference && <span className="block text-xs text-ink-faint">{p.reference}</span>}
                  </Td>
                  <Td className="text-right">
                    {isAdmin && !p.voided && (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                          const reason = prompt("Why is this payment being voided?");
                          if (reason?.trim()) act(() => api(`/api/payments/${p.id}/void`, { body: { reason } }));
                        }}
                      >
                        Void
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </Table>
          </div>
        </>
      )}

      {plan.refunds.length > 0 && (
        <>
          <h3 className="mt-5 mb-2 text-sm font-semibold">Refunds</h3>
          <ul className="space-y-2">
            {plan.refunds.map((r) => (
              <RefundRow key={r.id} refund={r} canWrite={canWrite} isAdmin={isAdmin} act={act} />
            ))}
          </ul>
        </>
      )}

      <Modal open={paying} onClose={() => setPaying(false)} title={`Record payment · ${plan.name}`}>
        {paying && (
          <PaymentForm
            plan={plan}
            onDone={() => {
              setPaying(false);
              onChanged();
            }}
          />
        )}
      </Modal>
      <Modal open={refunding} onClose={() => setRefunding(false)} title="Request refund">
        {refunding && (
          <RefundForm
            plan={plan}
            onDone={() => {
              setRefunding(false);
              onChanged();
            }}
          />
        )}
      </Modal>
    </Card>
  );
}

export function RefundRow({ refund: r, canWrite, isAdmin, act }: { refund: RefundView; canWrite: boolean; isAdmin: boolean; act: (fn: () => Promise<unknown>) => void }) {
  return (
    <li className="rounded-lg border border-line p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium tabular-nums">{formatRupees(r.amount)}</span>
        <Badge tone={REFUND_TONE[r.status]}>{label(r.status)}</Badge>
      </div>
      <p className="mt-1 text-ink-soft">{r.reason}</p>
      <p className="mt-1 text-xs text-ink-faint">
        Requested by {r.requestedBy?.fullName} · {formatDateTime(r.requestedAt)}
        {r.decidedBy && ` · ${label(r.status).toLowerCase()} by ${r.decidedBy.fullName}`}
        {r.decisionNote && ` (${r.decisionNote})`}
        {r.paidOn && ` · paid ${formatDate(r.paidOn)}${r.paidReference ? ` ref ${r.paidReference}` : ""}`}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        {isAdmin && r.status === "REQUESTED" && (
          <>
            <Button size="sm" onClick={() => act(() => api(`/api/refunds/${r.id}/decide`, { body: { approve: true } }))}>
              Approve
            </Button>
            <Button
              size="sm"
              variant="danger"
              onClick={() => {
                const note = prompt("Reason for rejecting?");
                if (note?.trim()) act(() => api(`/api/refunds/${r.id}/decide`, { body: { approve: false, note } }));
              }}
            >
              Reject
            </Button>
          </>
        )}
        {canWrite && r.status === "APPROVED" && (
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              const ref = prompt("Bank / UPI reference for the refund payment (optional)") ?? "";
              act(() => api(`/api/refunds/${r.id}/paid`, { body: { paidOn: today(), reference: ref } }));
            }}
          >
            Mark paid today
          </Button>
        )}
      </div>
    </li>
  );
}

function Money({ k, v, sub, strong }: { k: string; v: number; sub?: string; strong?: boolean }) {
  return (
    <div className="rounded-lg bg-muted p-3">
      <dt className="text-xs text-ink-faint">{k}</dt>
      <dd className={cx("mt-2 font-display text-[1.75rem] leading-none font-semibold", strong && "text-red-700")}>{formatRupees(v)}</dd>
      {sub && <dd className="text-xs text-ink-soft">{sub}</dd>}
    </div>
  );
}

function CreatePlan({ studentId, onDone }: { studentId: number; onDone: () => void }) {
  const packages = useApi<PackageView[]>("/api/fee-packages");
  const [packageId, setPackageId] = useState("");
  const [name, setName] = useState("");
  const [total, setTotal] = useState("");
  const [discount, setDiscount] = useState("0");
  const [startDate, setStartDate] = useState(today());
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pkg = packages.data?.find((p) => String(p.id) === packageId);

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await api(`/api/students/${studentId}/fee-plans`, {
            body: {
              packageId: packageId ? Number(packageId) : null,
              name: name || null,
              totalAmount: total ? Number(total) : null,
              discount: Number(discount || 0),
              startDate,
              notes: notes || null,
            },
          });
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
      <Field label="Service package" hint="Instalments follow the package schedule, scaled to any discount">
        {(id) => (
          <Select
            id={id}
            value={packageId}
            onChange={(e) => setPackageId(e.target.value)}
            placeholder="No package (single payment)"
            options={(packages.data ?? []).filter((p) => p.active).map((p) => ({ value: String(p.id), label: `${p.name} · ${formatRupees(p.totalAmount)}` }))}
          />
        )}
      </Field>
      {pkg && pkg.installments.length > 0 && (
        <ul className="rounded-lg bg-muted p-3 text-xs text-ink-soft">
          {pkg.installments.map((i) => (
            <li key={i.label}>
              {i.label}: {formatRupees(i.amount)} · day {i.dueOffsetDays}
            </li>
          ))}
        </ul>
      )}
      <div className="grid gap-3 sm:grid-cols-3">
        {!pkg && (
          <Field label="Total amount (₹)" required>
            {(id) => <Input id={id} type="number" inputMode="decimal" required min={0} value={total} onChange={(e) => setTotal(e.target.value)} />}
          </Field>
        )}
        <Field label="Discount (₹)">{(id) => <Input id={id} type="number" inputMode="decimal" min={0} value={discount} onChange={(e) => setDiscount(e.target.value)} />}</Field>
        <Field label="Start date">{(id) => <Input id={id} type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />}</Field>
      </div>
      <Field label="Plan name (optional)">{(id) => <Input id={id} maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder={pkg?.name ?? "Consultancy fee"} />}</Field>
      <Field label="Notes">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} />}</Field>
      <Button type="submit" loading={saving}>
        Create plan
      </Button>
    </form>
  );
}

function PaymentForm({ plan, onDone }: { plan: PlanView; onDone: () => void }) {
  const firstDue = plan.installments.find((i) => i.balance > 0);
  const [amount, setAmount] = useState(String(firstDue?.balance ?? plan.balance));
  const [method, setMethod] = useState("UPI");
  const [reference, setReference] = useState("");
  const [paidOn, setPaidOn] = useState(today());
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<{ id: number; receiptNo: string } | null>(null);

  if (done) {
    return (
      <div className="space-y-4">
        <Alert tone="green">
          Payment recorded. Receipt <b className="font-mono">{done.receiptNo}</b>.
        </Alert>
        <div className="flex gap-2">
          <Link href={`/receipts/${done.id}`} className="inline-flex items-center rounded-lg bg-brand-600 px-3.5 py-2 text-sm font-medium text-on-brand hover:bg-brand-500">
            Open receipt
          </Link>
          <Button variant="secondary" onClick={onDone}>
            Close
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          const p = await api<{ id: number; receiptNo: string }>(`/api/fee-plans/${plan.id}/payments`, {
            body: { installmentId: firstDue?.id ?? null, amount: Number(amount), method, reference: reference || null, paidOn, notes: notes || null },
          });
          setDone(p);
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <p className="text-sm text-ink-soft">
        Balance due {formatRupees(plan.balance)}
        {firstDue && ` · next: ${firstDue.label} (${formatRupees(firstDue.balance)})`}
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Amount (₹)" required>
          {(id) => <Input id={id} type="number" inputMode="decimal" required min={0.01} step="0.01" max={plan.balance} value={amount} onChange={(e) => setAmount(e.target.value)} />}
        </Field>
        <Field label="Paid on" required>{(id) => <Input id={id} type="date" required max={today()} value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />}</Field>
        <Field label="Method">{(id) => <Select id={id} value={method} onChange={(e) => setMethod(e.target.value)} options={PAYMENT_METHODS.filter((m) => m !== "GATEWAY")} labelFor={label} />}</Field>
        <Field label={method === "CASH" ? "Reference (optional)" : "UTR / cheque no."} required={method !== "CASH"}>
          {(id) => <Input id={id} maxLength={100} required={method !== "CASH"} value={reference} onChange={(e) => setReference(e.target.value)} />}
        </Field>
      </div>
      <Field label="Notes">{(id) => <Input id={id} maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} />}</Field>
      <Button type="submit" loading={saving}>
        Record payment
      </Button>
    </form>
  );
}

function RefundForm({ plan, onDone }: { plan: PlanView; onDone: () => void }) {
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await api(`/api/fee-plans/${plan.id}/refunds`, { body: { amount: Number(amount), reason } });
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
      <p className="text-sm text-ink-soft">Paid so far {formatRupees(plan.paid)}. An admin must approve before accounts pays it out.</p>
      <Field label="Amount (₹)" required>{(id) => <Input id={id} type="number" inputMode="decimal" required min={0.01} step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />}</Field>
      <Field label="Reason" required>{(id) => <Textarea id={id} required rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      <Button type="submit" loading={saving}>
        Submit request
      </Button>
    </form>
  );
}
