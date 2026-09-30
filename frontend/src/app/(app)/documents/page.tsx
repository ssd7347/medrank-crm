"use client";

import Link from "next/link";

import { DocStatusBadge } from "@/components/documents-tab";
import { Alert, Card, EmptyState, Loading, PageHeader, Table, Td, cx } from "@/components/ui";
import { formatDate, formatDateTime } from "@/lib/format";
import type { QueueRow } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";
import { useNow } from "@/lib/use-now";

/** Documentation desk (spec 4.7): what needs checking, and certificates about to expire. */
export default function DocumentQueuePage() {
  const { data, error, loading } = useApi<{ awaitingVerification: QueueRow[]; expiringSoon: QueueRow[] }>("/api/documents/queue");
  const now = useNow();

  const rows = (list: QueueRow[], dateCol: "updated" | "expiry") =>
    !list.length ? (
      <EmptyState title="Nothing here" />
    ) : (
      <Table head={["Student", "Document", "Status", dateCol === "updated" ? "Uploaded / updated" : "Valid until", "Counsellor"]}>
        {list.map((r) => {
          const expired = r.validUntil && new Date(r.validUntil).getTime() < now;
          return (
            <tr key={r.documentId}>
              <Td>
                <Link href={`/students/${r.studentId}?tab=documents`} className="font-medium text-brand-800 hover:underline">
                  {r.studentName}
                </Link>
              </Td>
              <Td>{r.typeName}</Td>
              <Td>
                <DocStatusBadge status={r.status} />
              </Td>
              <Td className={cx("whitespace-nowrap", dateCol === "expiry" && (expired ? "font-semibold text-red-700" : "text-amber-700"))}>
                {dateCol === "updated" ? formatDateTime(r.updatedAt) : formatDate(r.validUntil)}
              </Td>
              <Td>{r.counsellor?.fullName ?? "—"}</Td>
            </tr>
          );
        })}
      </Table>
    );

  return (
    <>
      <PageHeader title="Documents desk" subtitle="Scans waiting to be checked against originals, and certificates that expire within 30 days." />
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : (
        data && (
          <div className="space-y-6">
            <Card title={`Waiting for verification (${data.awaitingVerification.length})`}>
              <div className="-m-4">{rows(data.awaitingVerification, "updated")}</div>
            </Card>
            <Card title={`Expiring or expired (${data.expiringSoon.length})`}>
              <div className="-m-4">{rows(data.expiringSoon, "expiry")}</div>
            </Card>
          </div>
        )
      )}
    </>
  );
}
