"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { Alert, Badge, Card, Checkbox, EmptyState, Loading, Pagination, Select, Table, Td } from "@/components/ui";
import { CallStatusBadge, OutcomeBadge, purposeLabel } from "@/components/voice";
import { useAuth } from "@/lib/auth";
import { formatDateTime, label } from "@/lib/format";
import type { Page } from "@/lib/types";
import { PURPOSE_LABEL, VOICE_OUTCOMES, VOICE_PURPOSES, type VoiceCallRow } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

const STATUSES = ["COMPLETED", "NO_ANSWER", "FAILED", "SIMULATED", "DIALING", "CONNECTED"];

export default function VoiceCallsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Calls />
    </Suspense>
  );
}

function Calls() {
  const params = useSearchParams();
  const { hasRole } = useAuth();
  const admin = hasRole("SUPER_ADMIN");
  const [status, setStatus] = useState("");
  const [outcome, setOutcome] = useState("");
  const [purpose, setPurpose] = useState("");
  const [flagged, setFlagged] = useState(params.get("flagged") === "1");
  const [tests, setTests] = useState(false);
  const [page, setPage] = useState(0);
  const { data, error, loading } = useApi<Page<VoiceCallRow>>("/api/voice/calls", { status, outcome, purpose, flagged, tests, page, size: 25 });

  const filter = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement>) => {
    setter(e.target.value);
    setPage(0);
  };

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        <Select aria-label="Status" value={status} onChange={filter(setStatus)} placeholder="Any status" options={STATUSES} labelFor={label} />
        <Select aria-label="Outcome" value={outcome} onChange={filter(setOutcome)} placeholder="Any outcome" options={[...VOICE_OUTCOMES]} labelFor={label} />
        <Select aria-label="Kind of call" value={purpose} onChange={filter(setPurpose)} placeholder="Any kind of call" options={[...VOICE_PURPOSES]} labelFor={(p) => PURPOSE_LABEL[p as keyof typeof PURPOSE_LABEL]} />
      </div>
      <div className="flex flex-wrap gap-4">
        <Checkbox label="Flagged as wrong" checked={flagged} onChange={(e) => (setFlagged(e.target.checked), setPage(0))} />
        {admin && <Checkbox label="Test-console calls instead" checked={tests} onChange={(e) => (setTests(e.target.checked), setPage(0))} />}
      </div>
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.items.length ? (
            <EmptyState title="No AI calls match">{!admin && "You see calls with the students and leads assigned to you."}</EmptyState>
          ) : (
            <>
              <Table head={["When", "Who", "Call", "Status", "Outcome", ""]}>
                {data.items.map((c) => (
                  <tr key={c.id}>
                    <Td className="whitespace-nowrap">{formatDateTime(c.createdAt)}</Td>
                    <Td>
                      <span className="font-medium">{c.personName ?? "Unknown caller"}</span>
                      <span className="block text-xs text-ink-faint">{c.phone}</span>
                    </Td>
                    <Td>
                      {purposeLabel(c.purpose)}
                      <span className="block text-xs text-ink-faint">
                        {c.direction === "INBOUND" ? "They rang us" : "We rang them"}
                        {c.durationSec ? ` · ${Math.max(1, Math.round(c.durationSec / 60))} min` : ""}
                      </span>
                    </Td>
                    <Td>
                      <CallStatusBadge status={c.status} />
                    </Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        <OutcomeBadge outcome={c.outcome} />
                        {c.flaggedWrong && <Badge tone="red">Flagged</Badge>}
                        {c.testCall && <Badge tone="gray">Test</Badge>}
                      </div>
                    </Td>
                    <Td className="text-right">
                      <Link href={`/voice/calls/${c.id}`} className="text-sm font-medium text-brand-800 hover:underline">
                        Open
                      </Link>
                    </Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
            </>
          )}
        </div>
      </Card>
    </div>
  );
}
