"use client";

import Link from "next/link";
import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import { CALL_OUTCOMES, type Call, type CallOutcome } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, Card, EmptyState, Field, Input, Loading, Modal, Select, Textarea, cx, type Tone } from "./ui";

type Target = { leadId: number; studentId?: undefined } | { studentId: number; leadId?: undefined };

export const OUTCOME_TONE: Record<CallOutcome, Tone> = {
  CONNECTED: "green",
  NO_ANSWER: "amber",
  BUSY: "amber",
  SWITCHED_OFF: "amber",
  CALL_BACK_LATER: "blue",
  WRONG_NUMBER: "red",
};

/**
 * "Call" opens the phone's dialer (on a laptop, whatever app handles phone links) and immediately asks how
 * the call went, so the attempt is recorded even if nobody writes a note.
 */
export function CallButton({ target, phone, name, onLogged }: { target: Target; phone: string; name: string; onLogged?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <a
        href={`tel:${phone}`}
        onClick={() => setOpen(true)}
        className="inline-flex items-center justify-center gap-2 rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white hover:brightness-110"
      >
        Call
      </a>
      <Modal open={open} onClose={() => setOpen(false)} title={`Call with ${name}`}>
        {open && (
          <CallForm
            target={target}
            phone={phone}
            onDone={() => {
              setOpen(false);
              onLogged?.();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function CallForm({ target, phone, onDone }: { target: Target; phone: string; onDone: () => void }) {
  const [outcome, setOutcome] = useState<CallOutcome | null>(null);
  const [minutes, setMinutes] = useState("");
  const [notes, setNotes] = useState("");
  const [direction, setDirection] = useState("OUTBOUND");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!outcome) return;
    setSaving(true);
    setError(null);
    try {
      await api("/api/calls", {
        body: { ...target, phone, direction, outcome, durationSeconds: minutes ? Math.round(Number(minutes) * 60) : null, notes },
      });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <p className="text-sm text-ink-soft">
        Dialling <b className="text-ink tabular-nums">{phone}</b>. When the call ends, record how it went.
      </p>
      <fieldset>
        <legend className="mb-1.5 text-xs font-medium text-ink-soft">How did it go?</legend>
        <div className="grid grid-cols-2 gap-2">
          {CALL_OUTCOMES.map((o) => (
            <button
              key={o}
              type="button"
              aria-pressed={outcome === o}
              onClick={() => setOutcome(o)}
              className={cx("rounded-lg border px-3 py-2 text-left text-sm", outcome === o ? "border-brand-600 bg-brand-50 font-medium text-brand-800" : "border-line hover:bg-muted")}
            >
              {label(o)}
            </button>
          ))}
        </div>
      </fieldset>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Who called">
          {(id) => (
            <Select
              id={id}
              value={direction}
              onChange={(e) => setDirection(e.target.value)}
              options={[
                { value: "OUTBOUND", label: "We called them" },
                { value: "INBOUND", label: "They called us" },
              ]}
            />
          )}
        </Field>
        {outcome === "CONNECTED" && (
          <Field label="Length (minutes)">{(id) => <Input id={id} type="number" inputMode="decimal" min={0} max={240} step="0.5" value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>
        )}
      </div>
      <Field label="What was discussed">{(id) => <Textarea id={id} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />}</Field>
      <Button type="submit" loading={saving} disabled={!outcome}>
        Save call
      </Button>
    </form>
  );
}

/** Call history for a student (a lead's calls appear in its activity timeline instead). */
export function CallHistory({ studentId, version }: { studentId: number; version?: number }) {
  const { data, error, loading } = useApi<Call[]>(`/api/students/${studentId}/calls`, { v: version ?? 0 });
  return (
    <Card title="Call history">
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : !data?.length ? (
        <EmptyState title="No calls logged yet">Use the Call button at the top of this page; it records each attempt.</EmptyState>
      ) : (
        <ul className="-my-2 divide-y divide-line">
          {data.map((c) => (
            <li key={c.id} className="py-2.5 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={OUTCOME_TONE[c.outcome]}>{label(c.outcome)}</Badge>
                <span className="text-xs text-ink-soft">
                  {c.direction === "INBOUND" ? "They called" : "We called"} · {formatDateTime(c.calledAt)}
                  {c.calledBy ? ` · ${c.calledBy.fullName}` : c.provider === "AI_AGENT" ? " · AI agent" : ""}
                  {c.durationSeconds ? ` · ${Math.round(c.durationSeconds / 60)} min` : ""}
                </span>
              </div>
              {c.notes && <p className="mt-1 whitespace-pre-wrap">{c.notes}</p>}
              {c.voiceCallId && (
                <Link href={`/voice/calls/${c.voiceCallId}`} className="mt-1 inline-block text-xs font-medium text-brand-800 hover:underline">
                  Open transcript
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
