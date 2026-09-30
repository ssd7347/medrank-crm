"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, label } from "@/lib/format";
import type { OutboundMessage, ShortlistItem } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, ButtonLink, Card, EmptyState, Loading, Table, Td } from "./ui";

export function ShortlistTab({ studentId }: { studentId: number }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data, error, loading, reload } = useApi<ShortlistItem[]>(`/api/students/${studentId}/shortlist`);
  const [actionError, setActionError] = useState<string | null>(null);

  return (
    <Card title="Shortlisted colleges" actions={<ButtonLink href={`/predictor?studentId=${studentId}`}>Open predictor</ButtonLink>}>
      <div className="-m-4">
        {(error || actionError) && (
          <div className="p-3">
            <Alert>{error ?? actionError}</Alert>
          </div>
        )}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="Nothing shortlisted yet">Run the predictor for this student and add colleges from the results.</EmptyState>
        ) : (
          <Table head={["College", "State", "Course", "Quota", "Chance", ""]}>
            {data.map((s) => (
              <tr key={s.id}>
                <Td>
                  <Link href={`/colleges/${s.collegeId}`} className="font-medium text-brand-800 hover:underline">
                    {s.collegeName}
                  </Link>
                </Td>
                <Td>{s.state}</Td>
                <Td>{s.course}</Td>
                <Td>{s.quota}</Td>
                <Td>{s.band ? <Badge tone={s.band === "HIGH" ? "green" : s.band === "MODERATE" ? "amber" : "red"}>{label(s.band)} chance</Badge> : "—"}</Td>
                <Td className="text-right">
                  {canEdit && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={async () => {
                        try {
                          await api(`/api/students/${studentId}/shortlist/${s.id}`, { method: "DELETE" });
                          reload();
                        } catch (e) {
                          setActionError(errorMessage(e));
                        }
                      }}
                    >
                      Remove
                    </Button>
                  )}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </div>
    </Card>
  );
}

export function MessagesTab({ studentId }: { studentId: number }) {
  const { hasRole } = useAuth();
  const canAck = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data, error, loading, reload } = useApi<OutboundMessage[]>(`/api/students/${studentId}/messages`);
  const [actionError, setActionError] = useState<string | null>(null);
  const simulated = data?.some((m) => m.status === "SIMULATED");

  return (
    <div className="space-y-3">
      {simulated && (
        <Alert tone="blue">
          No WhatsApp/SMS provider is connected yet, so messages are logged here but not actually delivered. Call the family for anything urgent.
        </Alert>
      )}
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      <Card title="Messages to student & parent">
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No messages yet">Deadline reminders and result alerts appear here automatically.</EmptyState>
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((m) => (
              <li key={m.id} className="py-3">
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  {m.priority === "URGENT" && <Badge tone="red">Urgent</Badge>}
                  <Badge tone={m.status === "FAILED" ? "red" : m.status === "QUEUED" ? "amber" : "gray"}>{label(m.status)}</Badge>
                  <span className="text-ink-soft">
                    {label(m.channel)} to {label(m.recipientLabel).toLowerCase()} · {m.recipient}
                  </span>
                  <span className="text-ink-faint">{formatDateTime(m.createdAt)}</span>
                </div>
                <p className="mt-1 text-sm">{m.body}</p>
                <div className="mt-1 flex flex-wrap items-center gap-2 text-xs">
                  {m.acknowledgedAt ? (
                    <span className="text-emerald-700">Acknowledged {formatDateTime(m.acknowledgedAt)}</span>
                  ) : (
                    m.priority === "URGENT" && (
                      <>
                        {m.escalatedAt && <span className="font-medium text-red-700">Escalated: call the family</span>}
                        {canAck && (
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={async () => {
                              try {
                                await api(`/api/messages/${m.id}/acknowledge`, { method: "POST" });
                                reload();
                              } catch (e) {
                                setActionError(errorMessage(e));
                              }
                            }}
                          >
                            Family acknowledged
                          </Button>
                        )}
                      </>
                    )
                  )}
                  {m.lastError && <span className="text-red-700">{m.lastError}</span>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
