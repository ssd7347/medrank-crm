"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { Alert, Button, Card, EmptyState, Field, Loading, PageHeader, Select, Table, Td, cx } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { countdown, formatDateTime } from "@/lib/format";
import type { Round, RoundDesk } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";
import { useNow } from "@/lib/use-now";

/** Result-day view (spec 4.13): what closes soon, which seats still need a decision, unanswered alerts. */
export default function RoundDeskPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const desk = useApi<RoundDesk>("/api/counselling/desk");
  const [scanMsg, setScanMsg] = useState<string | null>(null);
  const now = useNow();

  // Refresh every minute; this page is meant to stay open on result days.
  const { reload } = desk;
  useEffect(() => {
    const t = setInterval(reload, 60_000);
    return () => clearInterval(t);
  }, [reload]);

  return (
    <>
      <PageHeader
        title="Round desk"
        subtitle="Live view for result days. Refreshes every minute."
        actions={
          isAdmin && (
            <Button
              variant="secondary"
              onClick={async () => {
                try {
                  const r = await api<{ choiceFillingAlerts: number; decisionAlerts: number }>("/api/counselling/scan-deadlines", { method: "POST" });
                  setScanMsg(`Deadline scan done: ${r.choiceFillingAlerts} choice-filling and ${r.decisionAlerts} decision alerts raised.`);
                  reload();
                } catch (e) {
                  setScanMsg(errorMessage(e));
                }
              }}
            >
              Run deadline scan now
            </Button>
          )
        }
      />
      {scanMsg && (
        <div className="mb-4">
          <Alert tone="blue">{scanMsg}</Alert>
        </div>
      )}
      {desk.error && <Alert>{desk.error}</Alert>}
      {desk.loading && !desk.data ? (
        <Loading />
      ) : (
        desk.data && (
          <div className="space-y-6">
            {desk.data.unacknowledgedEscalations > 0 && (
              <Alert>
                <b>{desk.data.unacknowledgedEscalations}</b> urgent message{desk.data.unacknowledgedEscalations === 1 ? " has" : "s have"} had no acknowledgement and were
                escalated. Check your notifications and call those families.
              </Alert>
            )}
            <div className="grid gap-6 lg:grid-cols-5">
              <Card title="Decisions pending" className="lg:col-span-3">
                <div className="-m-4">
                  {!desk.data.decisionsPending.length ? (
                    <EmptyState title="No seats waiting for a decision" />
                  ) : (
                    <Table head={["Student", "Seat", "Round", "Deadline", "Counsellor"]}>
                      {desk.data.decisionsPending.map((r) => {
                        const hoursLeft = (new Date(r.decisionDeadline).getTime() - now) / 3_600_000;
                        return (
                          <tr key={r.allotmentId} className={cx(hoursLeft < 24 && "bg-red-50/60")}>
                            <Td>
                              <Link href={`/students/${r.studentId}?tab=counselling`} className="font-medium text-brand-800 hover:underline">
                                {r.studentName}
                              </Link>
                              <a href={`tel:+91${r.studentPhone}`} className="block text-xs text-ink-soft hover:underline">
                                {r.studentPhone}
                              </a>
                            </Td>
                            <Td>
                              {r.college.name} <span className="text-xs text-ink-soft">({r.quota})</span>
                            </Td>
                            <Td>{r.roundLabel}</Td>
                            <Td className="whitespace-nowrap">
                              <span className={cx(hoursLeft < 24 && "font-semibold text-red-700")}>{countdown(r.decisionDeadline)}</span>
                            </Td>
                            <Td>{r.counsellor?.fullName ?? "—"}</Td>
                          </tr>
                        );
                      })}
                    </Table>
                  )}
                </div>
              </Card>
              <Card title="Next 7 days" className="lg:col-span-2">
                {!desk.data.upcomingDeadlines.length ? (
                  <EmptyState title="No deadlines this week" />
                ) : (
                  <ul className="-my-2 divide-y divide-line">
                    {desk.data.upcomingDeadlines.map((d) => (
                      <li key={`${d.roundId}-${d.kind}`} className="flex items-start justify-between gap-3 py-2">
                        <div>
                          <p className="text-sm font-medium">{d.kind}</p>
                          <p className="text-xs text-ink-soft">{d.roundLabel}</p>
                        </div>
                        <div className="text-right text-xs">
                          <p className="font-medium">{countdown(d.at)}</p>
                          <p className="text-ink-faint">{formatDateTime(d.at)}</p>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>
            {isAdmin && <BulkResults onDone={reload} />}
          </div>
        )
      )}
    </>
  );
}

function BulkResults({ onDone }: { onDone: () => void }) {
  const year = new Date().getFullYear();
  const rounds = useApi<Round[]>("/api/counselling/rounds", { year });
  const [roundId, setRoundId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "green" | "red"; text: string; rows?: { row: number; message: string }[] } | null>(null);

  return (
    <Card title="Upload a round's results (CSV)">
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (!file || !roundId) return;
          setBusy(true);
          setMessage(null);
          try {
            const form = new FormData();
            form.append("file", file);
            const r = await api<{ recorded: number; noAllotment: number }>(`/api/counselling/rounds/${roundId}/allotments/import`, { form, method: "POST" });
            setMessage({ tone: "green", text: `Recorded ${r.recorded} allotments and ${r.noAllotment} no-allotments. Families and counsellors have been alerted.` });
            onDone();
          } catch (err) {
            const rows = err instanceof ApiError ? (err.body.errors as unknown as { row: number; message: string }[] | undefined) : undefined;
            setMessage({ tone: "red", text: errorMessage(err), rows: Array.isArray(rows) ? rows : undefined });
          } finally {
            setBusy(false);
          }
        }}
        className="grid items-end gap-3 md:grid-cols-[16rem_1fr_auto]"
      >
        <Field label="Round">
          {(id) => (
            <Select id={id} required value={roundId} onChange={(e) => setRoundId(e.target.value)} placeholder="Choose…" options={(rounds.data ?? []).map((r) => ({ value: String(r.id), label: r.label }))} />
          )}
        </Field>
        <input
          type="file"
          accept=".csv,text/csv"
          aria-label="CSV file"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-brand-50 file:px-3 file:py-2 file:text-sm file:font-medium file:text-brand-800"
        />
        <Button type="submit" loading={busy} disabled={!file || !roundId}>
          Upload
        </Button>
      </form>
      <p className="mt-3 text-xs text-ink-faint">
        Columns: <code>neet_roll_no, college_code, course, quota, category</code>. Leave college_code empty for &quot;no allotment&quot;. Students are matched by NEET roll number
        and must already have a track for that authority and year. One bad row rejects the whole file.
      </p>
      {message && (
        <div className="mt-3 space-y-2">
          <Alert tone={message.tone}>{message.text}</Alert>
          {message.rows && (
            <ul className="max-h-48 overflow-auto rounded-lg bg-muted p-3 text-xs">
              {message.rows.map((r) => (
                <li key={r.row}>
                  <b>Row {r.row}:</b> {r.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Card>
  );
}
