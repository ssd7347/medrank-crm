"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Field, Input, Loading, Modal, Select, Textarea } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import { LANGUAGES, type Language } from "@/lib/types";
import { PURPOSE_LABEL, VOICE_PURPOSES, type VoicePurpose, type VoiceScript } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

export default function ScriptsPage() {
  const { data, error, loading, reload } = useApi<VoiceScript[]>("/api/voice/scripts");
  const [draft, setDraft] = useState<Partial<VoiceScript> | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const approve = async (s: VoiceScript) => {
    setBusy(true);
    setActionError(null);
    try {
      await api(`/api/voice/scripts/${s.id}/approve`, { method: "POST" });
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (loading && !data) return <Loading />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-sm text-ink-soft">
          What the AI is told to say and never say. Changing a script makes a new version; only a version you approve is used on real calls. The starter English
          scripts are drafts from the specification: read them before approving. Tamil and Hindi versions should be written by a native speaker.
        </p>
        <Button onClick={() => setDraft({ purpose: "DEADLINE_REMINDER", language: "ENGLISH", model: "claude-haiku-4-5-20251001" })}>New script</Button>
      </div>
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      {VOICE_PURPOSES.map((p) => {
        const versions = (data ?? []).filter((s) => s.purpose === p);
        return (
          <Card key={p} title={PURPOSE_LABEL[p]}>
            {versions.length === 0 ? (
              <p className="text-sm text-ink-faint">No script yet.</p>
            ) : (
              <ul className="-my-2 divide-y divide-line">
                {versions.map((s) => (
                  <li key={s.id} className="py-2.5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2 text-sm">
                        <span className="font-medium">
                          {label(s.language)} v{s.version}
                        </span>
                        {s.active ? <Badge tone="green">In use</Badge> : s.approved ? <Badge tone="gray">Retired</Badge> : <Badge tone="amber">Draft</Badge>}
                        <span className="text-xs text-ink-faint">
                          {s.model}
                          {s.approvedAt && ` · approved ${formatDateTime(s.approvedAt)}${s.approvedBy ? ` by ${s.approvedBy}` : ""}`}
                        </span>
                      </div>
                      <div className="flex gap-2">
                        <Button size="sm" variant="ghost" onClick={() => setOpen(open === s.id ? null : s.id)}>
                          {open === s.id ? "Hide" : "Read"}
                        </Button>
                        <Button size="sm" variant="secondary" onClick={() => setDraft({ ...s })}>
                          New version
                        </Button>
                        {!s.active && (
                          <Button size="sm" loading={busy} onClick={() => approve(s)}>
                            Approve
                          </Button>
                        )}
                      </div>
                    </div>
                    <p className="mt-1 text-sm text-ink-soft">&ldquo;{s.openingLine}&rdquo;</p>
                    {open === s.id && <pre className="mt-2 max-h-96 overflow-auto rounded-lg bg-muted p-3 text-xs whitespace-pre-wrap">{s.systemPrompt}</pre>}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        );
      })}
      <Modal open={draft !== null} onClose={() => setDraft(null)} title="New script version" wide>
        {draft && (
          <ScriptForm
            initial={draft}
            onSaved={() => {
              setDraft(null);
              reload();
            }}
            onCancel={() => setDraft(null)}
          />
        )}
      </Modal>
    </div>
  );
}

function ScriptForm({ initial, onSaved, onCancel }: { initial: Partial<VoiceScript>; onSaved: () => void; onCancel: () => void }) {
  const [purpose, setPurpose] = useState<VoicePurpose>(initial.purpose ?? "DEADLINE_REMINDER");
  const [language, setLanguage] = useState<Language>(initial.language ?? "ENGLISH");
  const [model, setModel] = useState(initial.model ?? "");
  const [opening, setOpening] = useState(initial.openingLine ?? "");
  const [prompt, setPrompt] = useState(initial.systemPrompt ?? "");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFormError(null);
    try {
      await api("/api/voice/scripts", { body: { purpose, language, model, openingLine: opening, systemPrompt: prompt } });
      onSaved();
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Kind of call" required>
          {(id) => <Select id={id} value={purpose} options={VOICE_PURPOSES.map((p) => ({ value: p, label: PURPOSE_LABEL[p] }))} onChange={(e) => setPurpose(e.target.value as VoicePurpose)} />}
        </Field>
        <Field label="Language" required>
          {(id) => <Select id={id} value={language} options={[...LANGUAGES]} labelFor={label} onChange={(e) => setLanguage(e.target.value as Language)} />}
        </Field>
        <Field label="AI model" required hint="Fast model for reminders; a richer one for general questions.">
          {(id) => <Input id={id} value={model} required maxLength={60} onChange={(e) => setModel(e.target.value)} />}
        </Field>
      </div>
      <Field label="Opening line" required hint="Must say it is an AI assistant and that the call may be recorded. {{first_name}} and {{consultancy_name}} are filled in.">
        {(id) => <Textarea id={id} rows={3} value={opening} required maxLength={1000} onChange={(e) => setOpening(e.target.value)} />}
      </Field>
      <Field label="Instructions to the AI" required>
        {(id) => <Textarea id={id} rows={14} value={prompt} required maxLength={20000} className="font-mono text-xs" onChange={(e) => setPrompt(e.target.value)} />}
      </Field>
      {formError && <Alert>{formError}</Alert>}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" loading={saving}>
          Save as draft
        </Button>
      </div>
    </form>
  );
}
