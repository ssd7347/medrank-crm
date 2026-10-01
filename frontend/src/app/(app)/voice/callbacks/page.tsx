"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, EmptyState, Field, Loading, Modal, Textarea, cx } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, label, relativeTime } from "@/lib/format";
import type { CallbackItem } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

const REASON: Record<string, string> = {
  DECISION_ADVICE: "Wants advice on a seat decision",
  REFUND_OR_DISPUTE: "Refund or dispute",
  DISTRESS: "Sounded distressed",
  CALLER_REQUEST: "Asked for a person",
  TOOL_FAILURE: "Agent could not look something up",
  VERIFICATION_FAILED: "Could not verify identity",
  OUT_OF_SCOPE: "Question the agent could not answer",
  CALLBACK_REQUESTED: "Asked to be called back",
};

export default function CallbacksPage() {
  const { user } = useAuth();
  const [done, setDone] = useState(false);
  const { data, error, loading, reload } = useApi<CallbackItem[]>("/api/voice/callbacks", { done });
  const [closing, setClosing] = useState<CallbackItem | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const act = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setActionError(null);
    try {
      await fn();
      reload();
      return true;
    } catch (e) {
      setActionError(errorMessage(e));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const chip = (active: boolean, text: string, onClick: () => void) => (
    <button onClick={onClick} className={cx("shrink-0 rounded-full border px-3 py-1 text-xs", active ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}>
      {text}
    </button>
  );

  return (
    <div className="space-y-4">
      <div className="flex gap-1.5">
        {chip(!done, "To call", () => setDone(false))}
        {chip(done, "Done", () => setDone(true))}
      </div>
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : !data?.length ? (
        <Card>
          <EmptyState title={done ? "No finished call-backs yet" : "Nobody is waiting for a call-back"}>
            {!done && "When the AI agent hands a caller to a person, the call-back appears here."}
          </EmptyState>
        </Card>
      ) : (
        <ul className="space-y-3">
          {data.map((c) => (
            <li key={c.id} className={cx("rounded-xl border bg-surface p-4 shadow-sm", c.overdue ? "border-red-300" : "border-line")}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    {c.priority === "URGENT" && <Badge tone="red">Urgent</Badge>}
                    <span className="font-medium">
                      {c.studentId ? (
                        <Link href={`/students/${c.studentId}?tab=voice`} className="text-brand-800 hover:underline">
                          {c.personName}
                        </Link>
                      ) : c.leadId ? (
                        <Link href={`/leads/${c.leadId}`} className="text-brand-800 hover:underline">
                          {c.personName}
                        </Link>
                      ) : (
                        "Unknown caller"
                      )}
                    </span>
                    {c.phone && (
                      <a href={`tel:${c.phone}`} className="text-sm text-brand-700 hover:underline">
                        {c.phone}
                      </a>
                    )}
                  </div>
                  <p className="mt-1 text-sm font-medium text-ink">{REASON[c.reason] ?? label(c.reason)}</p>
                  {c.summary && <p className="mt-1 text-sm whitespace-pre-wrap text-ink-soft">{c.summary}</p>}
                  {c.preferredTimeText && <p className="mt-1 text-sm text-ink-soft">They said: &ldquo;{c.preferredTimeText}&rdquo;</p>}
                  <p className={cx("mt-2 text-xs", c.overdue ? "font-medium text-red-700" : "text-ink-faint")}>
                    {c.status === "DONE"
                      ? `Done ${formatDateTime(c.doneAt)}${c.doneNote ? ` · ${c.doneNote}` : ""}`
                      : `Call by ${formatDateTime(c.dueAt)} (${relativeTime(c.dueAt)})`}
                    {" · "}
                    {c.assignedTo ? `With ${c.assignedTo.id === user?.id ? "you" : c.assignedTo.fullName}` : "Not taken yet"}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {c.voiceCallId && (
                    <Link href={`/voice/calls/${c.voiceCallId}`} className="rounded-lg border border-line px-2.5 py-1.5 text-xs font-medium hover:bg-muted">
                      Transcript
                    </Link>
                  )}
                  {c.status !== "DONE" && !c.assignedTo && (
                    <Button size="sm" variant="secondary" loading={busy} onClick={() => act(() => api(`/api/voice/callbacks/${c.id}/assign`, { body: {} }))}>
                      I&rsquo;ll call
                    </Button>
                  )}
                  {c.status !== "DONE" && (
                    <Button
                      size="sm"
                      onClick={() => {
                        setClosing(c);
                        setNote("");
                      }}
                    >
                      Mark done
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
      <Modal open={closing !== null} onClose={() => setClosing(null)} title="Call-back done">
        <Field label="What happened?" hint="Optional. Shown with the call-back.">
          {(id) => <Textarea id={id} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />}
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setClosing(null)}>
            Cancel
          </Button>
          <Button
            loading={busy}
            onClick={async () => {
              if (closing && (await act(() => api(`/api/voice/callbacks/${closing.id}/done`, { body: { note } })))) setClosing(null);
            }}
          >
            Save
          </Button>
        </div>
      </Modal>
    </div>
  );
}
