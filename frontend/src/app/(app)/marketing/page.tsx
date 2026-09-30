"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { formatDate, formatRupees, label } from "@/lib/format";
import { LEAD_SOURCES } from "@/lib/types";
import type { Branch, Campaign, MarketingReport, Spend } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

const money = (n: number | null) => (n === null ? "—" : formatRupees(n));

export default function MarketingPage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [branchId, setBranchId] = useState("");
  const branches = useApi<Branch[]>("/api/branches");
  const { data, error, loading, reload } = useApi<MarketingReport>("/api/marketing/report", { from, to, branchId });
  const [editing, setEditing] = useState<Campaign | "new" | null>(null);
  const [spending, setSpending] = useState<Campaign | null>(null);

  return (
    <>
      <PageHeader
        title="Marketing"
        subtitle="What each channel and campaign cost, and how many leads and confirmed admissions it brought."
        actions={<Button onClick={() => setEditing("new")}>New campaign</Button>}
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-3 lg:max-w-3xl">
        <Field label="Leads created from">{(id) => <Input id={id} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />}</Field>
        <Field label="To">{(id) => <Input id={id} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />}</Field>
        {!!branches.data?.length && (
          <Field label="Branch">
            {(id) => (
              <Select
                id={id}
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
                placeholder="All branches"
                options={branches.data!.map((b) => ({ value: String(b.id), label: b.name }))}
              />
            )}
          </Field>
        )}
      </div>

      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {loading && !data ? (
        <Loading />
      ) : data ? (
        <div className="space-y-6">
          <p className="text-sm text-ink-soft">
            {formatDate(data.from)} to {formatDate(data.to)}
            {!from && !to && " (last 12 months)"}
          </p>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="Leads" value={String(data.totalLeads)} />
            <Stat label="Admissions confirmed" value={String(data.totalAdmissions)} />
            <Stat label="Total cost" value={formatRupees(data.totalCost)} hint="Campaign spend + associate commission" />
            <Stat
              label="Cost per admission"
              value={data.totalAdmissions ? formatRupees(Math.round(data.totalCost / data.totalAdmissions)) : "—"}
              hint={data.totalLeads ? `${formatRupees(Math.round(data.totalCost / data.totalLeads))} per lead` : undefined}
            />
          </div>

          <Card title="By channel">
            <div className="-m-4">
              {!data.channels.length ? (
                <EmptyState title="No leads in this period" />
              ) : (
                <Table head={["Channel", "Leads", "Became students", "Admissions", "Campaign spend", "Commission", "Cost / lead", "Cost / admission"]}>
                  {data.channels.map((c) => (
                    <tr key={c.channel}>
                      <Td className="font-medium">{label(c.channel)}</Td>
                      <Td className="tabular-nums">{c.leads}</Td>
                      <Td className="tabular-nums">{c.converted}</Td>
                      <Td className="tabular-nums">
                        {c.admissions}
                        {c.leads > 0 && <span className="ml-1 text-xs text-ink-faint">({Math.round((100 * c.admissions) / c.leads)}%)</span>}
                      </Td>
                      <Td className="tabular-nums">{formatRupees(c.campaignSpend)}</Td>
                      <Td className="tabular-nums">{c.commissions ? formatRupees(c.commissions) : "—"}</Td>
                      <Td className="tabular-nums">{money(c.costPerLead)}</Td>
                      <Td className="tabular-nums">{money(c.costPerAdmission)}</Td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
          </Card>

          <Card title="Campaigns">
            <div className="-m-4">
              {!data.campaigns.length ? (
                <EmptyState title="No campaigns yet">Create one for each seminar, camp or ad run, then pick it when adding a lead.</EmptyState>
              ) : (
                <Table head={["Campaign", "Channel", "Dates", "Spent / budget", "Leads", "Admissions", "Cost / lead", "Cost / admission", ""]}>
                  {data.campaigns.map((c) => (
                    <tr key={c.id}>
                      <Td>
                        <Link href={`/leads?campaign=${c.id}`} className="font-medium text-brand-800 hover:underline">
                          {c.name}
                        </Link>
                        {!c.active && <span className="ml-2 align-middle"><Badge>Ended</Badge></span>}
                        {c.branch && <span className="block text-xs text-ink-faint">{c.branch.name}</span>}
                      </Td>
                      <Td className="whitespace-nowrap">{label(c.channel)}</Td>
                      <Td className="text-sm whitespace-nowrap">
                        {c.startDate ? formatDate(c.startDate) : "—"}
                        {c.endDate && ` to ${formatDate(c.endDate)}`}
                      </Td>
                      <Td className="tabular-nums">
                        <span className={c.budget !== null && c.spent > c.budget ? "font-medium text-red-700" : undefined}>{formatRupees(c.spent)}</span>
                        {c.budget !== null && <span className="text-ink-faint"> / {formatRupees(c.budget)}</span>}
                      </Td>
                      <Td className="tabular-nums">{c.leads}</Td>
                      <Td className="tabular-nums">{c.admissions}</Td>
                      <Td className="tabular-nums">{money(c.costPerLead)}</Td>
                      <Td className="tabular-nums">{money(c.costPerAdmission)}</Td>
                      <Td className="text-right whitespace-nowrap">
                        <Button size="sm" variant="secondary" onClick={() => setSpending(c)}>
                          Spend
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => setEditing(c)}>
                          Edit
                        </Button>
                      </Td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
          </Card>
        </div>
      ) : null}

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "New campaign" : "Edit campaign"}>
        {editing !== null && (
          <CampaignForm
            campaign={editing === "new" ? undefined : editing}
            branches={branches.data ?? []}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
      <Modal open={spending !== null} onClose={() => setSpending(null)} title={`Spend · ${spending?.name ?? ""}`}>
        {spending && <SpendPanel campaign={spending} onChanged={reload} />}
      </Modal>
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

function CampaignForm({ campaign, branches, onDone }: { campaign?: Campaign; branches: Branch[]; onDone: () => void }) {
  const [v, setV] = useState({
    name: campaign?.name ?? "",
    channel: campaign?.channel ?? "SEMINAR",
    branchId: campaign?.branch?.id.toString() ?? "",
    startDate: campaign?.startDate ?? "",
    endDate: campaign?.endDate ?? "",
    budget: campaign?.budget?.toString() ?? "",
    notes: campaign?.notes ?? "",
    active: campaign?.active ?? true,
  });
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
        branchId: v.branchId ? Number(v.branchId) : null,
        startDate: v.startDate || null,
        endDate: v.endDate || null,
        budget: v.budget === "" ? null : Number(v.budget),
      };
      if (campaign) await api(`/api/marketing/campaigns/${campaign.id}`, { method: "PUT", body });
      else await api("/api/marketing/campaigns", { body });
      onDone();
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Campaign name" required error={fieldErrors.name} hint="e.g. Madurai NEET seminar, June Instagram ads">
        {(id) => <Input id={id} required maxLength={120} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Channel" required hint="Shown for leads with this source">
          {(id) => <Select id={id} value={v.channel} onChange={(e) => setV({ ...v, channel: e.target.value as Campaign["channel"] })} options={LEAD_SOURCES} labelFor={label} />}
        </Field>
        <Field label="Budget (₹)" error={fieldErrors.budget}>
          {(id) => <Input id={id} type="number" inputMode="numeric" min={0} value={v.budget} onChange={(e) => setV({ ...v, budget: e.target.value })} />}
        </Field>
        <Field label="Starts">{(id) => <Input id={id} type="date" value={v.startDate} onChange={(e) => setV({ ...v, startDate: e.target.value })} />}</Field>
        <Field label="Ends">{(id) => <Input id={id} type="date" value={v.endDate} min={v.startDate || undefined} onChange={(e) => setV({ ...v, endDate: e.target.value })} />}</Field>
      </div>
      {branches.length > 0 && (
        <Field label="Branch" hint="Leave empty if every branch can tag leads with it">
          {(id) => (
            <Select
              id={id}
              value={v.branchId}
              onChange={(e) => setV({ ...v, branchId: e.target.value })}
              placeholder="All branches"
              options={branches.map((b) => ({ value: String(b.id), label: b.name }))}
            />
          )}
        </Field>
      )}
      <Field label="Notes">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={v.notes} onChange={(e) => setV({ ...v, notes: e.target.value })} />}</Field>
      <Checkbox label="Running (can be chosen on new leads)" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}

function SpendPanel({ campaign, onChanged }: { campaign: Campaign; onChanged: () => void }) {
  const list = useApi<Spend[]>(`/api/marketing/campaigns/${campaign.id}/spend`);
  const [v, setV] = useState({ spentOn: new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Kolkata" }), amount: "", note: "" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const total = (list.data ?? []).reduce((t, s) => t + s.amount, 0);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(`/api/marketing/campaigns/${campaign.id}/spend`, { body: { spentOn: v.spentOn, amount: Number(v.amount), note: v.note } });
      setV({ ...v, amount: "", note: "" });
      list.reload();
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-4">
      {(error || list.error) && <Alert>{error ?? list.error}</Alert>}
      <form onSubmit={add} className="grid gap-3 sm:grid-cols-[9rem_8rem_1fr_auto] sm:items-end">
        <Field label="Date" required>
          {(id) => <Input id={id} type="date" required value={v.spentOn} onChange={(e) => setV({ ...v, spentOn: e.target.value })} />}
        </Field>
        <Field label="Amount (₹)" required>
          {(id) => <Input id={id} type="number" inputMode="numeric" required min={1} step="0.01" value={v.amount} onChange={(e) => setV({ ...v, amount: e.target.value })} />}
        </Field>
        <Field label="What for">{(id) => <Input id={id} maxLength={300} value={v.note} onChange={(e) => setV({ ...v, note: e.target.value })} />}</Field>
        <Button type="submit" loading={saving}>
          Add
        </Button>
      </form>
      {list.loading && !list.data ? (
        <Loading />
      ) : !list.data?.length ? (
        <p className="text-sm text-ink-soft">Nothing recorded yet.</p>
      ) : (
        <>
          <ul className="divide-y divide-line rounded-lg border border-line">
            {list.data.map((s) => (
              <li key={s.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                <span className="min-w-0">
                  <span className="font-medium tabular-nums">{formatRupees(s.amount)}</span>
                  <span className="text-ink-soft"> · {formatDate(s.spentOn)}</span>
                  {s.note && <span className="block truncate text-xs text-ink-soft">{s.note}</span>}
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={async () => {
                    if (!confirm(`Remove this ${formatRupees(s.amount)} entry?`)) return;
                    try {
                      await api(`/api/marketing/spend/${s.id}`, { method: "DELETE" });
                      list.reload();
                      onChanged();
                    } catch (err) {
                      setError(errorMessage(err));
                    }
                  }}
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
          <p className="text-sm">
            Total spent: <b className="tabular-nums">{formatRupees(total)}</b>
            {campaign.budget !== null && <span className="text-ink-soft"> of {formatRupees(campaign.budget)} budget</span>}
          </p>
        </>
      )}
    </div>
  );
}
