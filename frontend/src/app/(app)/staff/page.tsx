"use client";

import { useState } from "react";

import { Alert, Button, Card, Checkbox, EmptyState, Field, Loading, PageHeader, Select, Table, Td, cx } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import type { StaffRow } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

const PERIODS = [
  { value: "7", label: "Last 7 days" },
  { value: "30", label: "Last 30 days" },
  { value: "90", label: "Last 90 days" },
  { value: "365", label: "Last 12 months" },
];

/** Counsellor & staff management (spec 4.11). */
export default function StaffPage() {
  const [days, setDays] = useState("30");
  const { data, error, loading, reload } = useApi<StaffRow[]>("/api/staff/performance", { days });

  return (
    <>
      <PageHeader
        title="Team workload & performance"
        subtitle="Leads and admissions are counted for leads created in the chosen period."
        actions={<Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS} />}
      />
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No front-line staff yet" />
          ) : (
            <Table head={["Name", "Open leads", "New leads", "Became students", "Admissions", "Admission rate", "Students", "Overdue follow-ups", "Open tickets"]}>
              {data.map((r) => (
                <tr key={r.userId}>
                  <Td>
                    <span className="font-medium">{r.fullName}</span>
                    <span className="block text-xs text-ink-faint">{label(r.role)}</span>
                  </Td>
                  <Td className="tabular-nums">{r.openLeads}</Td>
                  <Td className="tabular-nums">{r.newLeadsInPeriod}</Td>
                  <Td className="tabular-nums">{r.convertedToStudent}</Td>
                  <Td className="font-semibold tabular-nums">{r.admissionsConfirmed}</Td>
                  <Td className="tabular-nums">{r.newLeadsInPeriod ? `${r.admissionRate}%` : "—"}</Td>
                  <Td className="tabular-nums">{r.studentsAssigned}</Td>
                  <Td className={cx("tabular-nums", r.overdueFollowUps > 0 && "font-semibold text-red-700")}>{r.overdueFollowUps}</Td>
                  <Td className="tabular-nums">{r.openTickets}</Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      {data && data.length > 1 && <Reassign staff={data} onDone={reload} />}
    </>
  );
}

function Reassign({ staff, onDone }: { staff: StaffRow[]; onDone: () => void }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [what, setWhat] = useState({ leads: true, students: true, followUps: true });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: "green" | "red"; text: string } | null>(null);
  const options = staff.map((s) => ({ value: String(s.userId), label: `${s.fullName} · ${label(s.role)}` }));

  return (
    <Card title="Move work to someone else" className="mt-6">
      <p className="mb-3 text-sm text-ink-soft">Use when someone leaves or is overloaded. Only open leads and pending follow-ups move; closed history stays with the original person.</p>
      {msg && (
        <div className="mb-3">
          <Alert tone={msg.tone}>{msg.text}</Alert>
        </div>
      )}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (!confirm("Move the selected work? This cannot be undone automatically.")) return;
          setBusy(true);
          setMsg(null);
          try {
            const r = await api<{ leads: number; students: number; followUps: number }>("/api/staff/reassign", {
              body: { fromUserId: Number(from), toUserId: Number(to), ...what },
            });
            setMsg({ tone: "green", text: `Moved ${r.leads} leads, ${r.students} students and ${r.followUps} follow-ups.` });
            onDone();
          } catch (err) {
            setMsg({ tone: "red", text: errorMessage(err) });
          } finally {
            setBusy(false);
          }
        }}
        className="grid items-end gap-3 md:grid-cols-[1fr_1fr_auto]"
      >
        <Field label="From">{(id) => <Select id={id} required value={from} onChange={(e) => setFrom(e.target.value)} placeholder="Choose…" options={options} />}</Field>
        <Field label="To">{(id) => <Select id={id} required value={to} onChange={(e) => setTo(e.target.value)} placeholder="Choose…" options={options.filter((o) => o.value !== from)} />}</Field>
        <Button type="submit" loading={busy} disabled={!from || !to}>
          Move
        </Button>
        <div className="flex flex-wrap gap-4 md:col-span-3">
          <Checkbox label="Open leads" checked={what.leads} onChange={(e) => setWhat({ ...what, leads: e.target.checked })} />
          <Checkbox label="Students" checked={what.students} onChange={(e) => setWhat({ ...what, students: e.target.checked })} />
          <Checkbox label="Pending follow-ups" checked={what.followUps} onChange={(e) => setWhat({ ...what, followUps: e.target.checked })} />
        </div>
      </form>
    </Card>
  );
}
