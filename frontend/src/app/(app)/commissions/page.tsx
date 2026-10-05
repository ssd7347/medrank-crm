"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, EmptyState, Loading, PageHeader, Table, Td, cx, type Tone } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatRupees, label } from "@/lib/format";
import type { CommissionView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

const TONE: Record<CommissionView["status"], Tone> = { PENDING: "amber", APPROVED: "blue", PAID: "green", CANCELLED: "gray" };
const FILTERS = ["", "PENDING", "APPROVED", "PAID"] as const;

export default function CommissionsPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const [status, setStatus] = useState<(typeof FILTERS)[number]>("PENDING");
  const { data, error, loading, reload } = useApi<CommissionView[]>("/api/commissions", { status });
  const [actionError, setActionError] = useState<string | null>(null);

  async function act(fn: () => Promise<unknown>) {
    setActionError(null);
    try {
      await fn();
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader title="Referral commissions" subtitle="Created automatically when a referred lead's admission is confirmed. Amount = associate's rate × the student's consultancy fee after discount." />
      <div className="mb-3 flex gap-1.5 overflow-x-auto">
        {FILTERS.map((f) => (
          <button key={f} onClick={() => setStatus(f)} className={cx("shrink-0 rounded-md border px-3 py-1.5 font-medium transition-colors text-xs", status === f ? "border-brand-600 bg-brand-600 text-on-brand" : "border-line bg-surface")}>
            {f ? label(f) : "All"}
          </button>
        ))}
      </div>
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No commissions here" />
          ) : (
            <Table head={["Associate", "Student", "Basis", "Rate", "Commission", "Status", ""]}>
              {data.map((c) => (
                <tr key={c.id}>
                  <Td className="font-medium">{c.associateName}</Td>
                  <Td>
                    <Link href={c.studentId ? `/students/${c.studentId}` : `/leads/${c.leadId}`} className="text-brand-700 hover:underline">
                      {c.leadName}
                    </Link>
                  </Td>
                  <Td className="tabular-nums">{formatRupees(c.basisAmount)}</Td>
                  <Td className="tabular-nums">{c.rate}%</Td>
                  <Td className="font-semibold tabular-nums">{formatRupees(c.amount)}</Td>
                  <Td>
                    <Badge tone={TONE[c.status]}>{label(c.status)}</Badge>
                    {c.paidOn && <span className="block text-xs text-ink-faint">{formatDate(c.paidOn)}</span>}
                  </Td>
                  <Td className="text-right whitespace-nowrap">
                    {isAdmin && c.status === "PENDING" && (
                      <>
                        <Button size="sm" onClick={() => act(() => api(`/api/commissions/${c.id}/approve`, { method: "POST" }))}>
                          Approve
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => confirm("Cancel this commission?") && act(() => api(`/api/commissions/${c.id}/cancel`, { method: "POST" }))}>
                          Cancel
                        </Button>
                      </>
                    )}
                    {c.status === "APPROVED" && (
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          const ref = prompt("Payment reference (optional)") ?? "";
                          act(() => api(`/api/commissions/${c.id}/paid`, { body: { paidOn: new Date().toISOString().slice(0, 10), reference: ref } }));
                        }}
                      >
                        Mark paid
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
    </>
  );
}
