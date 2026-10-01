"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { OUTBOUND_PURPOSES, PURPOSE_HELP, PURPOSE_LABEL, type Campaign, type CampaignDetail, type CampaignStatus, type VoicePurpose } from "@/lib/types-voice";

import { Alert, Button, Field, Input, Select, type Tone } from "./ui";

export const CAMPAIGN_TONE: Record<CampaignStatus, Tone> = { DRAFT: "gray", RUNNING: "green", PAUSED: "amber", DONE: "blue" };

/** Create a campaign, or change one that is not running. */
export function CampaignForm({ existing, onSaved, onCancel }: { existing?: Campaign; onSaved: (d: CampaignDetail) => void; onCancel: () => void }) {
  const [name, setName] = useState(existing?.name ?? "");
  const [purpose, setPurpose] = useState<VoicePurpose>(existing?.purpose ?? "DEADLINE_REMINDER");
  const [windowStart, setWindowStart] = useState(existing?.windowStart.slice(0, 5) ?? "09:00");
  const [windowEnd, setWindowEnd] = useState(existing?.windowEnd.slice(0, 5) ?? "20:00");
  const [lookahead, setLookahead] = useState(String(existing?.lookaheadDays ?? 3));
  const [attempts, setAttempts] = useState(String(existing?.maxAttempts ?? 3));
  const [gap, setGap] = useState(String(existing?.retryGapMin ?? 240));
  const [concurrent, setConcurrent] = useState(String(existing?.maxConcurrent ?? 3));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFormError(null);
    const body = {
      name,
      purpose,
      windowStart,
      windowEnd,
      lookaheadDays: Number(lookahead),
      maxAttempts: Number(attempts),
      retryGapMin: Number(gap),
      maxConcurrent: Number(concurrent),
    };
    try {
      onSaved(
        existing
          ? await api<CampaignDetail>(`/api/voice/campaigns/${existing.id}`, { method: "PUT", body })
          : await api<CampaignDetail>("/api/voice/campaigns", { body }),
      );
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" required>
          {(id) => <Input id={id} value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} placeholder="e.g. Round 2 choice filling" />}
        </Field>
        <Field label="Kind of call" required hint={PURPOSE_HELP[purpose]}>
          {(id) => (
            <Select
              id={id}
              value={purpose}
              disabled={!!existing}
              options={OUTBOUND_PURPOSES.map((p) => ({ value: p, label: PURPOSE_LABEL[p] }))}
              onChange={(e) => setPurpose(e.target.value as VoicePurpose)}
            />
          )}
        </Field>
        <Field label="Call from (IST)" hint="Never earlier than the legal start in the server settings.">
          {(id) => <Input id={id} type="time" value={windowStart} required onChange={(e) => setWindowStart(e.target.value)} />}
        </Field>
        <Field label="Call until (IST)" hint="Never later than the legal end in the server settings.">
          {(id) => <Input id={id} type="time" value={windowEnd} required onChange={(e) => setWindowEnd(e.target.value)} />}
        </Field>
        <Field label={purpose === "MISSED_CALL_FOLLOWUP" ? "Look back (days)" : "Look ahead (days)"} hint="How far ahead to look for a date or due fee.">
          {(id) => <Input id={id} type="number" min={1} max={30} value={lookahead} onChange={(e) => setLookahead(e.target.value)} />}
        </Field>
        <Field label="Tries per person" hint="If nobody answers.">
          {(id) => <Input id={id} type="number" min={1} max={5} value={attempts} onChange={(e) => setAttempts(e.target.value)} />}
        </Field>
        <Field label="Wait before trying again (minutes)">
          {(id) => <Input id={id} type="number" min={30} max={1440} value={gap} onChange={(e) => setGap(e.target.value)} />}
        </Field>
        <Field label="Calls at the same time" hint="Keep within your telephony plan's channels.">
          {(id) => <Input id={id} type="number" min={1} max={20} value={concurrent} onChange={(e) => setConcurrent(e.target.value)} />}
        </Field>
      </div>
      {formError && <Alert>{formError}</Alert>}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" loading={saving}>
          {existing ? "Save" : "Create and find people"}
        </Button>
      </div>
    </form>
  );
}
