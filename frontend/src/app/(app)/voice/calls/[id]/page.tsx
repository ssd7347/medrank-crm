"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Alert, Badge, Button, Card, DescList, Field, Loading, Modal, Table, Td, Textarea } from "@/components/ui";
import { CallStatusBadge, OutcomeBadge, Transcript, purposeLabel } from "@/components/voice";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import type { VoiceCallDetail } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

export default function VoiceCallPage() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, setData } = useApi<VoiceCallDetail>(`/api/voice/calls/${id}`);
  const [flagging, setFlagging] = useState(false);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [flagError, setFlagError] = useState<string | null>(null);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Call not found"}</Alert>;
  const c = data.call;

  const flag = async () => {
    setSaving(true);
    setFlagError(null);
    try {
      setData(await api<VoiceCallDetail>(`/api/voice/calls/${id}/flag`, { body: { note } }));
      setFlagging(false);
    } catch (e) {
      setFlagError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="text-sm">
        <Link href="/voice/calls" className="text-ink-soft hover:text-ink">
          ← All calls
        </Link>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-lg font-semibold">
            {purposeLabel(c.purpose)}
            {c.personName && <span className="font-normal text-ink-soft"> · {c.personName}</span>}
          </h2>
          <div className="mt-1 flex flex-wrap gap-1.5">
            <CallStatusBadge status={c.status} />
            <OutcomeBadge outcome={c.outcome} />
            {c.testCall && <Badge tone="gray">Test call</Badge>}
            {c.flaggedWrong && <Badge tone="red">Flagged as wrong</Badge>}
            <Badge tone={c.verified ? "green" : "gray"}>{c.verified ? "Identity verified" : "Not verified"}</Badge>
          </div>
        </div>
        {!c.flaggedWrong && (
          <Button variant="secondary" onClick={() => setFlagging(true)}>
            Flag as wrong
          </Button>
        )}
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
        <Card title="Conversation">
          <Transcript lines={data.transcript} empty={c.status === "SIMULATED" ? "Not dialled, so there is no conversation" : "No transcript (none was sent, or it has passed the retention period)"} />
        </Card>
        <div className="space-y-6">
          <Card title="Details">
            <DescList
              items={[
                ["Who", c.studentId ? <Link href={`/students/${c.studentId}?tab=voice`} className="text-brand-800 hover:underline">{c.personName}</Link> : c.leadId ? <Link href={`/leads/${c.leadId}`} className="text-brand-800 hover:underline">{c.personName}</Link> : "Unknown caller"],
                ["Number", c.phone],
                ["Direction", c.direction === "INBOUND" ? "They rang us" : "We rang them"],
                ["Language", label(c.language)],
                ["Started", formatDateTime(data.startedAt ?? c.createdAt)],
                ["Length", c.durationSec === null ? "—" : `${Math.floor(c.durationSec / 60)} min ${c.durationSec % 60} s`],
                ["Handed over because", c.handoffReason ? label(c.handoffReason) : null],
                ["How it ended", data.disconnectReason ? label(data.disconnectReason) : null],
                ["Script", data.script],
                ["Platform", c.provider],
                ["Recording", data.recordingRef ? <a href={data.recordingRef} target="_blank" rel="noreferrer" className="text-brand-800 hover:underline">Open recording</a> : data.recordingAllowed ? "None received" : "Not allowed (no recording consent)"],
              ]}
            />
            {c.summary && <p className="mt-4 rounded-lg bg-muted p-3 text-sm whitespace-pre-wrap">{c.summary}</p>}
            {data.outcomeNote && <p className="mt-2 text-sm text-ink-soft">Note: {data.outcomeNote}</p>}
            {data.flagNote && <p className="mt-2 text-sm text-red-700">Flagged: {data.flagNote}</p>}
          </Card>
          {data.callbacks.length > 0 && (
            <Card title="Call-back">
              {data.callbacks.map((cb) => (
                <p key={cb.id} className="text-sm">
                  {label(cb.reason)} · {cb.status === "DONE" ? `done ${formatDateTime(cb.doneAt)}` : `due ${formatDateTime(cb.dueAt)}`}
                  {cb.assignedTo && ` · ${cb.assignedTo.fullName}`}
                </p>
              ))}
            </Card>
          )}
        </div>
      </div>

      <Card title="What the agent looked up">
        <div className="-m-4">
          {data.tools.length === 0 ? (
            <p className="p-4 text-sm text-ink-faint">No look-ups on this call.</p>
          ) : (
            <Table head={["Time", "Look-up", "Result", "Took"]}>
              {data.tools.map((t, i) => (
                <tr key={i}>
                  <Td className="whitespace-nowrap">{formatDateTime(t.at)}</Td>
                  <Td className="font-mono text-xs">{t.tool}</Td>
                  <Td>
                    <Badge tone={t.status === "OK" ? "green" : t.status === "INTERNAL" ? "red" : "amber"}>{t.status === "OK" ? "OK" : label(t.status)}</Badge>
                  </Td>
                  <Td className="tabular-nums">{t.latencyMs === null ? "—" : `${t.latencyMs} ms`}</Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>

      <Modal open={flagging} onClose={() => setFlagging(false)} title="Flag this call as wrong">
        <p className="mb-3 text-sm text-ink-soft">Flagged calls are listed for the admin, who uses them to improve the script.</p>
        <Field label="What went wrong?">{(fid) => <Textarea id={fid} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />}</Field>
        {flagError && (
          <div className="mt-3">
            <Alert>{flagError}</Alert>
          </div>
        )}
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setFlagging(false)}>
            Cancel
          </Button>
          <Button loading={saving} onClick={flag}>
            Flag
          </Button>
        </div>
      </Modal>
    </div>
  );
}
