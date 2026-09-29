"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Button, Card, EmptyState, Loading, PageHeader, Select, Table, Td, cx } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime, relativeTime } from "@/lib/format";
import type { FollowUp } from "@/lib/types";
import { useApi } from "@/lib/use-api";

const RANGES = [
  { value: "0", label: "Due now & overdue" },
  { value: "1", label: "Next 24 hours" },
  { value: "7", label: "Next 7 days" },
  { value: "30", label: "Next 30 days" },
];

export default function FollowUpsPage() {
  const [days, setDays] = useState("7");
  const { data, error, loading, reload } = useApi<FollowUp[]>("/api/follow-ups/mine", { days });
  const [actionError, setActionError] = useState<string | null>(null);

  async function complete(id: number) {
    setActionError(null);
    try {
      await api(`/api/follow-ups/${id}/complete`, { method: "POST" });
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader
        title="My follow-ups"
        subtitle="Open follow-ups assigned to you, oldest first."
        actions={<Select aria-label="Range" value={days} onChange={(e) => setDays(e.target.value)} options={RANGES} />}
      />
      {(error || actionError) && (
        <div className="mb-4">
          <Alert>{error ?? actionError}</Alert>
        </div>
      )}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="Nothing due">Schedule follow-ups from a lead&apos;s page.</EmptyState>
          ) : (
            <Table head={["Due", "Lead", "Phone", "Purpose", ""]}>
              {data.map((f) => (
                <tr key={f.id} className={cx(f.overdue && "bg-red-50/50")}>
                  <Td className="whitespace-nowrap">
                    <span className={cx(f.overdue && "font-medium text-red-700")}>{relativeTime(f.dueAt)}</span>
                    <span className="block text-xs text-ink-faint">{formatDateTime(f.dueAt)}</span>
                  </Td>
                  <Td>
                    <Link href={`/leads/${f.leadId}`} className="font-medium text-brand-800 hover:underline">
                      {f.leadName}
                    </Link>
                  </Td>
                  <Td className="whitespace-nowrap tabular-nums">
                    <a href={`tel:+91${f.leadPhone}`} className="hover:underline">
                      {f.leadPhone}
                    </a>
                  </Td>
                  <Td>{f.purpose}</Td>
                  <Td className="text-right">
                    <Button size="sm" variant="secondary" onClick={() => complete(f.id)}>
                      Done
                    </Button>
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
