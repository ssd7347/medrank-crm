"use client";

import Link from "next/link";
import { useState } from "react";

import { RefundRow } from "@/components/fees-tab";
import { Alert, ButtonLink, Card, EmptyState, Loading, PageHeader, Table, Td, cx } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { FEE_WRITE_ROLES, useAuth } from "@/lib/auth";
import { formatDate, formatRupees } from "@/lib/format";
import type { DuesSummary, RefundView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";
import { useNow } from "@/lib/use-now";

export default function FeesPage() {
  const { hasRole } = useAuth();
  const canWrite = hasRole(...FEE_WRITE_ROLES);
  const isAdmin = hasRole("SUPER_ADMIN");
  const dues = useApi<DuesSummary>("/api/fees/dues");
  const refunds = useApi<RefundView[]>(canWrite ? "/api/refunds" : null);
  const [filter, setFilter] = useState<"all" | "overdue" | "week">("overdue");
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const rows = (dues.data?.rows ?? []).filter((r) =>
    filter === "overdue" ? r.overdueDays > 0 : filter === "week" ? r.overdueDays === 0 && new Date(r.dueDate).getTime() - now <= 7 * 86400000 : true,
  );

  return (
    <>
      <PageHeader
        title="Fees & dues"
        subtitle="Consultancy fees only (not college tuition)."
        actions={
          canWrite && (
            <>
              <ButtonLink href="/fees/packages" variant="secondary">
                Service packages
              </ButtonLink>
              <ButtonLink href="/commissions" variant="secondary">
                Commissions
              </ButtonLink>
            </>
          )
        }
      />
      {(dues.error || error) && <Alert>{dues.error ?? error}</Alert>}
      {dues.loading && !dues.data ? (
        <Loading />
      ) : (
        dues.data && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Tile k="Collected this month" v={dues.data.collectedThisMonth} />
              <Tile k="Outstanding" v={dues.data.outstanding} />
              <Tile k="Overdue" v={dues.data.overdue} tone="red" />
              <Tile k="Due in next 7 days" v={dues.data.dueNext7Days} tone="amber" />
            </div>

            <Card
              title="Unpaid instalments"
              actions={
                <div className="flex gap-1">
                  {(["overdue", "week", "all"] as const).map((f) => (
                    <button
                      key={f}
                      onClick={() => setFilter(f)}
                      className={cx("rounded-full border px-2.5 py-0.5 text-xs", filter === f ? "border-brand-600 bg-brand-600 text-white" : "border-line")}
                    >
                      {f === "overdue" ? "Overdue" : f === "week" ? "This week" : "All"}
                    </button>
                  ))}
                </div>
              }
            >
              <div className="-m-4">
                {!rows.length ? (
                  <EmptyState title="Nothing in this view" />
                ) : (
                  <Table head={["Student", "Instalment", "Due", "Balance", "Counsellor"]}>
                    {rows.map((r) => (
                      <tr key={r.installmentId} className={cx(r.overdueDays > 0 && "bg-red-50/60")}>
                        <Td>
                          <Link href={`/students/${r.studentId}?tab=fees`} className="font-medium text-brand-800 hover:underline">
                            {r.studentName}
                          </Link>
                          <a href={`tel:+91${r.studentPhone}`} className="block text-xs text-ink-soft hover:underline">
                            {r.studentPhone}
                          </a>
                        </Td>
                        <Td>{r.label}</Td>
                        <Td className="whitespace-nowrap">
                          {formatDate(r.dueDate)}
                          {r.overdueDays > 0 && <span className="block text-xs font-semibold text-red-700">{r.overdueDays} days late</span>}
                        </Td>
                        <Td className="font-medium tabular-nums">{formatRupees(r.balance)}</Td>
                        <Td>{r.counsellor?.fullName ?? "—"}</Td>
                      </tr>
                    ))}
                  </Table>
                )}
              </div>
            </Card>

            {canWrite && (
              <Card title="Refunds needing action">
                {!refunds.data?.length ? (
                  <EmptyState title="No open refunds" />
                ) : (
                  <ul className="space-y-2">
                    {refunds.data.map((r) => (
                      <RefundRow
                        key={r.id}
                        refund={r}
                        canWrite={canWrite}
                        isAdmin={isAdmin}
                        act={async (fn) => {
                          setError(null);
                          try {
                            await fn();
                            refunds.reload();
                          } catch (e) {
                            setError(errorMessage(e));
                          }
                        }}
                      />
                    ))}
                  </ul>
                )}
              </Card>
            )}
          </div>
        )
      )}
    </>
  );
}

function Tile({ k, v, tone }: { k: string; v: number; tone?: "red" | "amber" }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-4 shadow-sm">
      <p className="text-xs text-ink-faint">{k}</p>
      <p className={cx("mt-1 text-xl font-semibold tabular-nums", tone === "red" && v > 0 && "text-red-700", tone === "amber" && v > 0 && "text-amber-700")}>{formatRupees(v)}</p>
    </div>
  );
}
