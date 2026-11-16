"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Field, Input, Loading, Modal, Select, Textarea } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { ScriptKind, VoiceScript } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

export default function ScriptsPage() {
  const { data, error, loading, reload } = useApi<VoiceScript[]>("/api/voice/scripts");
  const identity = useApi<string[]>("/api/voice/scripts/identity-warnings");
  const [draft, setDraft] = useState<Partial<VoiceScript> | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<VoiceScript | null>(null);

  const approve = async (s: VoiceScript) => {
    setBusy(true);
    setActionError(null);
    try {
      await api(`/api/voice/scripts/${s.id}/approve`, { method: "POST" });
      setConfirming(null);
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
      setConfirming(null);
    } finally {
      setBusy(false);
    }
  };

  if (loading && !data) return <Loading />;
  const names = [...new Set((data ?? []).map((s) => s.name))];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-sm text-ink-soft">
          What the AI says and must never say, for each kind of call. Changing a script makes a new version; only a version you approve is used on real
          calls. The starter scripts are drafts: read and adjust them for this company before approving. Exactly one incoming-call script can be in use.
        </p>
        <Button onClick={() => setDraft({ name: "", kind: "OUTBOUND", model: "claude-sonnet-5-5" })}>New script</Button>
      </div>
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      {(identity.data ?? []).map((w) => (
        <Alert key={w} tone="amber">
          <span className="font-medium">Identity question: </span>
          {w}
        </Alert>
      ))}
      {names.map((name) => {
        const versions = (data ?? []).filter((s) => s.name === name);
        return (
          <Card key={name} title={<span className="flex items-center gap-2">{name}<Badge tone={versions[0].kind === "INBOUND" ? "indigo" : "teal"}>{versions[0].kind === "INBOUND" ? "Incoming calls" : "Outgoing calls"}</Badge></span>}>
            <ul className="-my-2 divide-y divide-line">
              {versions.map((s) => (
                <li key={s.id} className="py-2.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-medium">Version {s.version}</span>
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
                        <Button size="sm" loading={busy} onClick={() => (s.warnings?.length ? setConfirming(s) : approve(s))}>
                          Approve
                        </Button>
                      )}
                    </div>
                  </div>
                  <p className="mt-1 text-sm break-words text-ink-soft">&ldquo;{s.openingLine}&rdquo;</p>
                  {(s.warnings ?? []).map((w) => (
                    <p key={w} className="mt-1 text-xs text-amber-800">
                      {w}
                    </p>
                  ))}
                  {open === s.id && <pre className="mt-2 max-h-96 overflow-auto rounded-lg bg-muted p-3 text-xs whitespace-pre-wrap">{s.systemPrompt}</pre>}
                </li>
              ))}
            </ul>
          </Card>
        );
      })}
      {confirming && (
        <Modal open onClose={() => setConfirming(null)} title={`Approve version ${confirming.version} of ${confirming.name}?`}>
          <div className="space-y-4 text-sm">
            {confirming.warnings.map((w) => (
              <Alert key={w} tone="amber">
                {w}
              </Alert>
            ))}
            <p className="text-ink-soft">Approve it only if your compliance adviser has agreed to this opening line.</p>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setConfirming(null)}>
                Cancel
              </Button>
              <Button loading={busy} onClick={() => approve(confirming)}>
                Approve anyway
              </Button>
            </div>
          </div>
        </Modal>
      )}
      <Modal open={draft !== null} onClose={() => setDraft(null)} title={draft?.id ? `New version of ${draft.name}` : "New script"} wide>
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
  const [name, setName] = useState(initial.name ?? "");
  const [kind, setKind] = useState<ScriptKind>(initial.kind ?? "OUTBOUND");
  const [model, setModel] = useState(initial.model ?? "");
  const [opening, setOpening] = useState(initial.openingLine ?? "");
  const [prompt, setPrompt] = useState(initial.systemPrompt ?? "");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const isNewVersion = !!initial.id;

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFormError(null);
    try {
      await api("/api/voice/scripts", { body: { name, kind, model, openingLine: opening, systemPrompt: prompt } });
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
        <Field label="Name" required hint="e.g. Payment reminder">
          {(id) => <Input id={id} value={name} required maxLength={80} disabled={isNewVersion} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Used for">
          {(id) => (
            <Select
              id={id}
              value={kind}
              disabled={isNewVersion}
              options={[
                { value: "OUTBOUND", label: "Outgoing calls (campaigns)" },
                { value: "INBOUND", label: "Incoming calls" },
              ]}
              onChange={(e) => setKind(e.target.value as ScriptKind)}
            />
          )}
        </Field>
        <Field label="AI model" required hint="A fast model for reminders.">
          {(id) => <Input id={id} value={model} required maxLength={60} onChange={(e) => setModel(e.target.value)} />}
        </Field>
      </div>
      <Field label="Opening line" required hint="Must say it is an AI assistant. Say the call may be recorded only if recording is switched on, and do not say why you are calling until the person is verified. {{first_name}}, {{agent_name}} and {{company_name}} are filled in.">
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
