"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, label } from "@/lib/format";
import { useApi } from "@/lib/use-api";
import {
  PURPOSE_LABEL,
  type ConsentSource,
  type PhoneConsent,
  type TranscriptLine,
  type VoiceCallRow,
  type VoiceCallStatus,
  type VoiceOutcome,
  type VoiceOverview,
} from "@/lib/types-voice";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, Select, cx, type Tone } from "./ui";

// Shared pieces of the AI voice agent screens.

const TABS = [
  { href: "/voice", label: "Overview" },
  { href: "/voice/callbacks", label: "Call-backs" },
  { href: "/voice/calls", label: "Calls" },
  { href: "/voice/campaigns", label: "Campaigns", admin: true },
  { href: "/voice/scripts", label: "Scripts", admin: true },
  { href: "/voice/test", label: "Test console", admin: true },
  { href: "/voice/setup", label: "Provider setup", admin: true },
];

/** Tabs across the top of every /voice page; scrolls sideways on phones. */
export function VoiceTabs() {
  const pathname = usePathname();
  const { hasRole } = useAuth();
  const admin = hasRole("SUPER_ADMIN");
  return (
    <nav aria-label="AI voice agent" className="-mx-4 mb-5 flex gap-1 overflow-x-auto border-b border-line px-4 sm:mx-0 sm:px-0">
      {TABS.filter((t) => !t.admin || admin).map((t) => {
        const active = t.href === "/voice" ? pathname === "/voice" : pathname === t.href || pathname.startsWith(t.href + "/");
        return (
          <Link
            key={t.href}
            href={t.href}
            aria-current={active ? "page" : undefined}
            className={cx(
              "shrink-0 border-b-2 px-3 py-2 text-sm whitespace-nowrap",
              active ? "border-brand-600 font-medium text-brand-800" : "border-transparent text-ink-soft hover:text-ink",
            )}
          >
            {t.label}
          </Link>
        );
      })}
    </nav>
  );
}

/** Fired after "Pause all" changes, so the banner shows the new state at once. */
export const VOICE_STATE_CHANGED = "voice-state-changed";

/** Says plainly, on every voice page, whether any phone is actually ringing. */
export function SimulatedBanner() {
  const { data, reload } = useApi<VoiceOverview>("/api/voice/overview");
  useEffect(() => {
    window.addEventListener(VOICE_STATE_CHANGED, reload);
    return () => window.removeEventListener(VOICE_STATE_CHANGED, reload);
  }, [reload]);
  if (!data) return null;
  if (data.paused) {
    return (
      <div className="mb-4">
        <Alert tone="red">All AI calling is paused. No calls are being placed and incoming calls get the normal greeting.</Alert>
      </div>
    );
  }
  if (data.live) return null;
  return (
    <div className="mb-4">
      <Alert tone="amber">
        <strong>Simulated mode.</strong> No telephony or voice-AI provider is connected, so no phone rings. Campaign calls are recorded as
        &ldquo;Simulated&rdquo; and the test console uses a rule-based stand-in for the AI.
      </Alert>
    </div>
  );
}

const STATUS_TONE: Record<VoiceCallStatus, Tone> = {
  QUEUED: "gray",
  DIALING: "blue",
  CONNECTED: "blue",
  COMPLETED: "green",
  NO_ANSWER: "amber",
  FAILED: "red",
  SIMULATED: "indigo",
};

export function CallStatusBadge({ status }: { status: VoiceCallStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{status === "SIMULATED" ? "Simulated (not dialled)" : label(status)}</Badge>;
}

const OUTCOME_TONE: Record<VoiceOutcome, Tone> = {
  ACKNOWLEDGED: "green",
  WILL_ACT: "green",
  CALLBACK_REQUESTED: "blue",
  HANDED_OFF: "amber",
  NOT_INTERESTED: "gray",
  OPTED_OUT: "red",
  WRONG_PERSON: "gray",
  NO_INTERACTION: "gray",
  VERIFICATION_FAILED: "red",
};

export function OutcomeBadge({ outcome }: { outcome: VoiceOutcome | null }) {
  if (!outcome) return null;
  return <Badge tone={OUTCOME_TONE[outcome]}>{label(outcome)}</Badge>;
}

export function purposeLabel(p: string) {
  return PURPOSE_LABEL[p as keyof typeof PURPOSE_LABEL] ?? label(p);
}

/** A conversation laid out like a chat, with the agent's look-ups shown between the lines. */
export function Transcript({ lines, empty }: { lines: TranscriptLine[]; empty?: string }) {
  if (!lines.length) return <EmptyState title={empty ?? "No transcript"} />;
  return (
    <ol className="space-y-2">
      {lines.map((l, i) =>
        l.role === "tool" ? (
          <li key={i} className="flex justify-center">
            <span className="max-w-full rounded-md border border-dashed border-line px-2 py-1 font-mono text-[11px] break-words text-ink-faint">
              {l.tool} → {l.status}
              {l.text ? ` · ${l.text}` : ""}
            </span>
          </li>
        ) : (
          <li key={i} className={cx("flex", l.role === "caller" ? "justify-end" : "justify-start")}>
            <div
              className={cx(
                "max-w-[85%] rounded-lg px-3 py-2 text-sm break-words whitespace-pre-wrap sm:max-w-[75%]",
                l.role === "caller" ? "bg-brand-600 text-on-brand" : "bg-muted text-ink",
              )}
            >
              <span className={cx("block text-[10px] font-semibold tracking-wide uppercase", l.role === "caller" ? "text-on-brand/70" : "text-ink-faint")}>
                {l.role === "caller" ? "Caller" : "AI agent"}
              </span>
              {l.text}
            </div>
          </li>
        ),
      )}
    </ol>
  );
}

/** AI calls for one student or lead; each opens its transcript. */
export function VoiceCallList({ path }: { path: string }) {
  const { data, error, loading } = useApi<VoiceCallRow[]>(path);
  return (
    <Card title="AI calls">
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : !data?.length ? (
        <EmptyState title="No AI calls yet" />
      ) : (
        <ul className="-my-2 divide-y divide-line">
          {data.map((c) => (
            <li key={c.id} className="py-2.5 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <CallStatusBadge status={c.status} />
                <OutcomeBadge outcome={c.outcome} />
                <span className="text-xs text-ink-soft">
                  {purposeLabel(c.purpose)} · {formatDateTime(c.createdAt)}
                </span>
              </div>
              {c.summary && <p className="mt-1 text-ink-soft">{c.summary}</p>}
              <Link href={`/voice/calls/${c.id}`} className="mt-1 inline-block text-xs font-medium text-brand-800 hover:underline">
                Open transcript
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

const SOURCES: { value: ConsentSource; label: string }[] = [
  { value: "ONBOARDING", label: "Enrolment / onboarding form" },
  { value: "PAPER_FORM", label: "Signed paper form" },
  { value: "WEB_FORM", label: "Website form" },
  { value: "VERBAL_ON_CALL", label: "Said yes on a recorded call" },
];

const STATE_TONE: Record<PhoneConsent["state"], Tone> = { NONE: "gray", GRANTED: "green", REFUSED: "red", OPTED_OUT: "red" };
const STATE_TEXT: Record<PhoneConsent["state"], string> = {
  NONE: "Not asked yet",
  GRANTED: "AI calls allowed",
  REFUSED: "Said no",
  OPTED_OUT: "Asked to stop",
};

/**
 * Whether the AI agent may call each number, and record the call (spec 18.14.2). Nobody is ever dialled
 * without a "yes" recorded here; minors' parents should give it for the parent's number too.
 */
export function ConsentCard({ path, canEdit }: { path: string; canEdit: boolean }) {
  const { data, error, loading, setData } = useApi<PhoneConsent[]>(path);
  const [editing, setEditing] = useState<PhoneConsent | null>(null);
  const [ai, setAi] = useState(true);
  const [rec, setRec] = useState(false);
  const [source, setSource] = useState<ConsentSource>("ONBOARDING");
  const [evidence, setEvidence] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const open = (p: PhoneConsent) => {
    setEditing(p);
    setAi(p.state !== "GRANTED" ? true : p.aiCalls);
    setRec(p.recording);
    setSource(p.source ?? "ONBOARDING");
    setEvidence("");
    setFormError(null);
  };

  const save = async () => {
    if (!editing) return;
    setSaving(true);
    setFormError(null);
    try {
      setData(
        await api<PhoneConsent[]>(path, {
          body: { personType: editing.personType, aiCallConsent: ai, recordingConsent: ai && rec, source, evidenceRef: evidence || null },
        }),
      );
      setEditing(null);
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="Consent for AI calls">
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : (
        <ul className="-my-2 divide-y divide-line">
          {(data ?? []).map((p) => (
            <li key={p.personType} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-sm">
              <div className="min-w-0">
                <p className="font-medium">
                  {p.label} <span className="font-normal text-ink-faint">· {p.phone}</span>
                </p>
                <div className="mt-1 flex flex-wrap items-center gap-1.5">
                  <Badge tone={STATE_TONE[p.state]}>{STATE_TEXT[p.state]}</Badge>
                  {p.state === "GRANTED" && <Badge tone={p.recording ? "teal" : "gray"}>{p.recording ? "Recording allowed" : "No recording"}</Badge>}
                </div>
                {(p.capturedAt || p.revokedAt) && (
                  <p className="mt-1 text-xs text-ink-faint">
                    {p.revokedAt ? `${p.revokeNote ?? "Withdrawn"} · ${formatDateTime(p.revokedAt)}` : `${label(p.source)} · ${formatDateTime(p.capturedAt)}`}
                    {p.evidenceRef && !p.revokedAt && ` · ${p.evidenceRef}`}
                  </p>
                )}
              </div>
              {canEdit && (
                <Button size="sm" variant="secondary" onClick={() => open(p)}>
                  Record consent
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={`Consent for ${editing?.label ?? ""}`}>
        <div className="space-y-4">
          <p className="text-sm text-ink-soft">
            Record only what the family actually agreed to, for this number ({editing?.phone}). The usual wording: &ldquo;I agree to be contacted by
            phone, including automated/AI-assisted calls, and to call recording.&rdquo;
          </p>
          <Checkbox label="Agrees to AI-assisted calls" checked={ai} onChange={(e) => setAi(e.target.checked)} />
          <Checkbox label="Agrees to calls being recorded" checked={ai && rec} disabled={!ai} onChange={(e) => setRec(e.target.checked)} />
          <Field label="How was this given?" required>
            {(id) => <Select id={id} value={source} options={SOURCES} onChange={(e) => setSource(e.target.value as ConsentSource)} />}
          </Field>
          <Field label="Proof (form number, file name)" hint="Optional, but it helps if the consent is ever questioned.">
            {(id) => <Input id={id} value={evidence} maxLength={500} onChange={(e) => setEvidence(e.target.value)} />}
          </Field>
          {!ai && <Alert tone="amber">Saving this stops all AI calls to this number, including campaigns already running.</Alert>}
          {formError && <Alert>{formError}</Alert>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button loading={saving} onClick={save}>
              Save
            </Button>
          </div>
        </div>
      </Modal>
    </Card>
  );
}
