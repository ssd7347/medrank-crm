"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { INDIAN_STATES, formatDate, formatRupees } from "@/lib/format";
import type { AssociateFull, AssociatePerformance, Branch } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

export default function AssociatesPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const { data, error, loading, reload } = useApi<AssociatePerformance[]>("/api/referral-associates/performance");
  const [editing, setEditing] = useState<AssociateFull | "new" | null>(null);

  const totals = (data ?? []).reduce(
    (t, r) => ({
      leads: t.leads + r.leads,
      admissions: t.admissions + r.admissions,
      owed: t.owed + r.commissionPending + r.commissionApproved,
      paid: t.paid + r.commissionPaid,
    }),
    { leads: 0, admissions: 0, owed: 0, paid: 0 },
  );

  return (
    <>
      <PageHeader
        title="Associates & sub-agents"
        subtitle="District partners who refer students: territory, agreement, what they brought in and what they have earned."
        actions={isAdmin && <Button onClick={() => setEditing("new")}>Add associate</Button>}
      />
      {!!data?.length && (
        <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <Stat label="Leads referred" value={String(totals.leads)} />
          <Stat label="Admissions confirmed" value={String(totals.admissions)} />
          <Stat label="Commission still owed" value={formatRupees(totals.owed)} />
          <Stat label="Commission paid" value={formatRupees(totals.paid)} />
        </div>
      )}
      <Card
        actions={
          <Link href="/commissions" className="text-sm text-brand-700 hover:underline">
            Commission ledger →
          </Link>
        }
        title="Directory"
      >
        <div className="-m-4">
          {error && (
            <div className="p-3">
              <Alert>{error}</Alert>
            </div>
          )}
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No associates yet">Add your district partners so referral leads can be credited to them.</EmptyState>
          ) : (
            <Table head={["Associate", "Territory", "Rate", "Agreement", "Leads", "Admissions", "Owed", "Paid", ""]}>
              {data.map(({ associate: a, ...p }) => (
                <tr key={a.id}>
                  <Td>
                    <span className="font-medium">{a.fullName}</span>
                    {!a.active && <span className="ml-2 align-middle"><Badge>Inactive</Badge></span>}
                    <span className="block text-xs text-ink-soft tabular-nums">{a.phone}</span>
                  </Td>
                  <Td>
                    {[a.district, a.state].filter(Boolean).join(", ") || "—"}
                    {a.territory && <span className="block text-xs text-ink-soft">{a.territory}</span>}
                    {a.branch && <span className="block text-xs text-ink-faint">{a.branch.name} branch</span>}
                  </Td>
                  <Td className="tabular-nums">{a.commissionRate}%</Td>
                  <Td className="whitespace-nowrap">
                    {a.agreementEnd ? (
                      a.agreementExpired ? (
                        <Badge tone="red">Expired {formatDate(a.agreementEnd)}</Badge>
                      ) : (
                        <span className="text-sm">until {formatDate(a.agreementEnd)}</span>
                      )
                    ) : a.agreementStart ? (
                      <span className="text-sm">from {formatDate(a.agreementStart)}</span>
                    ) : (
                      <span className="text-ink-faint">Not recorded</span>
                    )}
                  </Td>
                  <Td className="tabular-nums">{p.leads}</Td>
                  <Td className="tabular-nums">
                    {p.admissions}
                    {p.leads > 0 && <span className="ml-1 text-xs text-ink-faint">({Math.round((100 * p.admissions) / p.leads)}%)</span>}
                  </Td>
                  <Td className="tabular-nums">{formatRupees(p.commissionPending + p.commissionApproved)}</Td>
                  <Td className="tabular-nums">{formatRupees(p.commissionPaid)}</Td>
                  <Td className="text-right">
                    {isAdmin && (
                      <Button size="sm" variant="ghost" onClick={() => setEditing(a)}>
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
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add associate" : "Edit associate"} wide>
        {editing !== null && (
          <AssociateForm
            associate={editing === "new" ? undefined : editing}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="group relative overflow-hidden rounded-lg border border-line bg-surface p-5 shadow-card transition duration-300 hover:border-brand-300 hover:shadow-lift">
      <p className="eyebrow text-ink-faint">{label}</p>
      <p className="mt-2.5 font-display text-3xl leading-none font-semibold">{value}</p>
    </div>
  );
}

function AssociateForm({ associate, onDone }: { associate?: AssociateFull; onDone: () => void }) {
  const [v, setV] = useState({
    fullName: associate?.fullName ?? "",
    phone: associate?.phone ?? "",
    email: associate?.email ?? "",
    district: associate?.district ?? "",
    state: associate?.state ?? "",
    territory: associate?.territory ?? "",
    commissionRate: associate?.commissionRate?.toString() ?? "0",
    agreementStart: associate?.agreementStart ?? "",
    agreementEnd: associate?.agreementEnd ?? "",
    agreementTerms: associate?.agreementTerms ?? "",
    branchId: associate?.branch?.id.toString() ?? "",
    active: associate?.active ?? true,
  });
  const branches = useApi<Branch[]>("/api/branches");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      const body = {
        ...v,
        commissionRate: Number(v.commissionRate),
        agreementStart: v.agreementStart || null,
        agreementEnd: v.agreementEnd || null,
        branchId: v.branchId ? Number(v.branchId) : null,
      };
      if (associate) await api(`/api/referral-associates/${associate.id}`, { method: "PUT", body });
      else await api("/api/referral-associates", { body });
      onDone();
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-5">
      {error && <Alert>{error}</Alert>}
      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold">Contact</legend>
        <Field label="Full name" required error={fieldErrors.fullName}>
          {(id) => <Input id={id} required maxLength={120} value={v.fullName} onChange={(e) => setV({ ...v, fullName: e.target.value })} />}
        </Field>
        <Field label="Phone" required error={fieldErrors.phone}>
          {(id) => <Input id={id} type="tel" required value={v.phone} onChange={(e) => setV({ ...v, phone: e.target.value })} />}
        </Field>
        <Field label="Email" error={fieldErrors.email}>
          {(id) => <Input id={id} type="email" maxLength={160} value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />}
        </Field>
      </fieldset>
      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold">Territory</legend>
        <Field label="District">{(id) => <Input id={id} maxLength={80} value={v.district} onChange={(e) => setV({ ...v, district: e.target.value })} />}</Field>
        <Field label="State">{(id) => <Select id={id} value={v.state} onChange={(e) => setV({ ...v, state: e.target.value })} options={INDIAN_STATES} placeholder="—" />}</Field>
        <Field label="Area covered" className="sm:col-span-2" hint="Towns, schools or coaching centres this associate covers">
          {(id) => <Input id={id} maxLength={300} value={v.territory} onChange={(e) => setV({ ...v, territory: e.target.value })} />}
        </Field>
        {!!branches.data?.length && (
          <Field label="Works with branch">
            {(id) => (
              <Select
                id={id}
                value={v.branchId}
                onChange={(e) => setV({ ...v, branchId: e.target.value })}
                placeholder="Any / head office"
                options={branches.data!.map((b) => ({ value: String(b.id), label: b.name }))}
              />
            )}
          </Field>
        )}
      </fieldset>
      <fieldset className="grid gap-4 sm:grid-cols-3">
        <legend className="mb-2 text-sm font-semibold">Agreement</legend>
        <Field label="Commission rate (%)" required error={fieldErrors.commissionRate} hint="Of the student's consultancy fee">
          {(id) => <Input id={id} type="number" required min={0} max={100} step="0.01" value={v.commissionRate} onChange={(e) => setV({ ...v, commissionRate: e.target.value })} />}
        </Field>
        <Field label="Agreement from">{(id) => <Input id={id} type="date" value={v.agreementStart} onChange={(e) => setV({ ...v, agreementStart: e.target.value })} />}</Field>
        <Field label="Agreement until">{(id) => <Input id={id} type="date" value={v.agreementEnd} onChange={(e) => setV({ ...v, agreementEnd: e.target.value })} />}</Field>
        <Field label="Terms" className="sm:col-span-3" hint="Anything agreed beyond the rate: payout timing, targets, exclusivity">
          {(id) => <Textarea id={id} maxLength={1000} value={v.agreementTerms} onChange={(e) => setV({ ...v, agreementTerms: e.target.value })} />}
        </Field>
      </fieldset>
      <Checkbox label="Active (can be chosen on new referral leads)" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
