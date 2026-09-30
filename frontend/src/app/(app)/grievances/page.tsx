"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { GrievanceForm } from "@/components/grievance-form";
import { Alert, Badge, Button, Card, EmptyState, Loading, Modal, PageHeader, Table, Td, cx, type Tone } from "@/components/ui";
import { GRIEVANCE_ROLES, useAuth } from "@/lib/auth";
import { formatDate, formatRupees, label } from "@/lib/format";
import type { GrievanceStatus, GrievanceView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

const GRIEVANCE_TONE: Record<GrievanceStatus, Tone> = {
  OPEN: "blue",
  UNDER_REVIEW: "indigo",
  ESCALATED: "red",
  RESOLVED: "green",
  CLOSED: "gray",
};

export default function GrievancesPage() {
  const router = useRouter();
  const { hasRole } = useAuth();
  const manager = hasRole(...GRIEVANCE_ROLES);
  const [openOnly, setOpenOnly] = useState(true);
  const [adding, setAdding] = useState(false);
  const { data, error, loading } = useApi<GrievanceView[]>("/api/grievances", { openOnly });

  return (
    <>
      <PageHeader
        title="Grievance register"
        subtitle={manager ? "Formal disputes and serious complaints, with a permanent record of every step." : "Grievances you have logged. The grievance officer handles them."}
        actions={<Button onClick={() => setAdding(true)}>Log grievance</Button>}
      />
      {manager && (
        <div className="mb-3 flex gap-1.5">
          {[true, false].map((o) => (
            <button
              key={String(o)}
              onClick={() => setOpenOnly(o)}
              className={cx("rounded-full border px-3 py-1 text-xs", openOnly === o ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}
            >
              {o ? "Open" : "All"}
            </button>
          ))}
        </div>
      )}
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No grievances" />
          ) : (
            <Table head={["Reference", "Complainant", "Category", "Amount", "Status", "Target date", "Officer"]}>
              {data.map((g) => (
                <tr key={g.id} className={cx(g.overdue && "bg-red-50/60")}>
                  <Td>
                    <Link href={`/grievances/${g.id}`} className="font-mono text-sm text-brand-800 hover:underline">
                      {g.referenceNo}
                    </Link>
                  </Td>
                  <Td>{g.complainantName}</Td>
                  <Td>{label(g.category)}</Td>
                  <Td className="tabular-nums">{g.amountInDispute ? formatRupees(g.amountInDispute) : "—"}</Td>
                  <Td>
                    <Badge tone={GRIEVANCE_TONE[g.status]}>{label(g.status)}</Badge>
                  </Td>
                  <Td className={cx("whitespace-nowrap", g.overdue && "font-semibold text-red-700")}>{formatDate(g.targetResolutionDate)}</Td>
                  <Td>{g.assignedOfficer?.fullName ?? "—"}</Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={adding} onClose={() => setAdding(false)} title="Log grievance" wide>
        {adding && <GrievanceForm onDone={(g) => router.push(`/grievances/${g.id}`)} />}
      </Modal>
    </>
  );
}
