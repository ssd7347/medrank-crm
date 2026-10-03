"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useBranding } from "@/lib/branding";
import { label } from "@/lib/format";
import { useStages } from "@/lib/stages";
import { type ContactStatus, type FieldDef, type ImportBatch, type TagCount } from "@/lib/types";
import { FIELD_OPS, FIELD_OP_LABEL, type Campaign, type CampaignDetail, type CampaignStatus, type FieldOp } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

import { Alert, Button, Checkbox, Field, Input, Select, type Tone } from "./ui";

export const CAMPAIGN_TONE: Record<CampaignStatus, Tone> = { DRAFT: "gray", RUNNING: "green", PAUSED: "amber", DONE: "blue" };

/** "Status New or Contacted, from customers.xlsx, Due date is a date within the next 3 days". */
export function describeFilter(c: Campaign, fields: FieldDef[], uploads?: ImportBatch[]) {
  const parts: string[] = [];
  parts.push(c.statuses.length ? `Status ${c.statuses.map(label).join(" or ")}` : "Any status except Do not call");
  if (c.tag) parts.push(`tagged "${c.tag}"`);
  if (c.importBatchId) parts.push(`from ${uploads?.find((u) => u.id === c.importBatchId)?.fileName ?? "one upload"}`);
  if (c.fieldKey && c.fieldOp) {
    const f = fields.find((x) => x.key === c.fieldKey)?.label ?? c.fieldKey;
    const op = c.fieldOp === "DUE_WITHIN_DAYS" ? `is a date within the next ${c.fieldValue ?? "3"} days`
      : c.fieldOp === "GREATER_THAN" || c.fieldOp === "EQUALS" ? `${FIELD_OP_LABEL[c.fieldOp]} ${c.fieldValue ?? ""}`
      : FIELD_OP_LABEL[c.fieldOp];
    parts.push(`${f} ${op}`.trim());
  }
  return parts.join(", ");
}

/** Create a campaign, or change one that is not running. */
export function CampaignForm({ existing, onSaved, onCancel }: { existing?: Campaign; onSaved: (d: CampaignDetail) => void; onCancel: () => void }) {
  const { plural } = useBranding();
  const { stages } = useStages();
  const scripts = useApi<string[]>("/api/voice/script-names");
  const fields = useApi<FieldDef[]>("/api/fields");
  const uploads = useApi<ImportBatch[]>("/api/contacts/imports");
  const tags = useApi<TagCount[]>("/api/tags");
  const [tag, setTag] = useState(existing?.tag ?? "");
  const [name, setName] = useState(existing?.name ?? "");
  const [scriptName, setScriptName] = useState(existing?.scriptName ?? "");
  const [statuses, setStatuses] = useState<ContactStatus[]>(existing?.statuses ?? []);
  const [importBatchId, setImportBatchId] = useState(existing?.importBatchId ? String(existing.importBatchId) : "");
  const [fieldKey, setFieldKey] = useState(existing?.fieldKey ?? "");
  const [fieldOp, setFieldOp] = useState<FieldOp>(existing?.fieldOp ?? "NOT_EMPTY");
  const [fieldValue, setFieldValue] = useState(existing?.fieldValue ?? "");
  const [windowStart, setWindowStart] = useState(existing?.windowStart.slice(0, 5) ?? "09:00");
  const [windowEnd, setWindowEnd] = useState(existing?.windowEnd.slice(0, 5) ?? "20:00");
  const [attempts, setAttempts] = useState(String(existing?.maxAttempts ?? 3));
  const [gap, setGap] = useState(String(existing?.retryGapMin ?? 240));
  const [concurrent, setConcurrent] = useState(String(existing?.maxConcurrent ?? 3));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const field = (fields.data ?? []).find((f) => f.key === fieldKey);
  const needsValue = fieldOp === "DUE_WITHIN_DAYS" || fieldOp === "GREATER_THAN" || fieldOp === "EQUALS";

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFormError(null);
    const body = {
      name,
      scriptName: scriptName || scripts.data?.[0],
      statuses,
      importBatchId: importBatchId ? Number(importBatchId) : null,
      tag: tag || null,
      fieldKey: fieldKey || null,
      fieldOp: fieldKey ? fieldOp : null,
      fieldValue: fieldKey && needsValue ? fieldValue : null,
      windowStart,
      windowEnd,
      maxAttempts: Number(attempts),
      retryGapMin: Number(gap),
      maxConcurrent: Number(concurrent),
    };
    try {
      onSaved(existing ? await api<CampaignDetail>(`/api/voice/campaigns/${existing.id}`, { method: "PUT", body }) : await api<CampaignDetail>("/api/voice/campaigns", { body }));
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-5">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" required>
          {(id) => <Input id={id} value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} placeholder="e.g. October payment reminders" />}
        </Field>
        <Field label="Script" required hint="What the AI says. Only an approved script can start a campaign.">
          {(id) => (
            <Select id={id} value={scriptName || scripts.data?.[0] || ""} options={(scripts.data ?? []).map((s) => ({ value: s, label: s }))} onChange={(e) => setScriptName(e.target.value)} />
          )}
        </Field>
      </div>

      <fieldset className="space-y-4 rounded-lg border border-line p-3">
        <legend className="px-1 text-sm font-medium">Who to call</legend>
        <div>
          <p className="mb-1.5 text-xs font-medium text-ink-soft">Status (none ticked = any status except &ldquo;Do not call&rdquo;)</p>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {stages.filter((st) => st.key !== "DO_NOT_CALL").map(({ key: s, label: stageLabel }) => (
              <Checkbox
                key={s}
                label={stageLabel}
                checked={statuses.includes(s)}
                onChange={(e) => setStatuses((old) => (e.target.checked ? [...old, s] : old.filter((x) => x !== s)))}
              />
            ))}
          </div>
        </div>
        <Field label="Only people with this tag">
          {(id) => (
            <Select
              id={id}
              value={tag}
              placeholder="(any tag)"
              options={(tags.data ?? []).map((t) => ({ value: t.tag, label: `${t.tag} (${t.contacts})` }))}
              onChange={(e) => setTag(e.target.value)}
            />
          )}
        </Field>
        <Field label="Only from this upload">
          {(id) => (
            <Select
              id={id}
              value={importBatchId}
              placeholder={`All ${plural.toLowerCase()}`}
              options={(uploads.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fileName} (${new Date(u.createdAt).toLocaleDateString("en-IN")})` }))}
              onChange={(e) => setImportBatchId(e.target.value)}
            />
          )}
        </Field>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="And where the field">
            {(id) => (
              <Select id={id} value={fieldKey} placeholder="(no field condition)" options={(fields.data ?? []).map((f) => ({ value: f.key, label: f.label }))} onChange={(e) => setFieldKey(e.target.value)} />
            )}
          </Field>
          {fieldKey && (
            <Field label="Condition">
              {(id) => <Select id={id} value={fieldOp} options={FIELD_OPS.map((o) => ({ value: o, label: FIELD_OP_LABEL[o] }))} onChange={(e) => setFieldOp(e.target.value as FieldOp)} />}
            </Field>
          )}
          {fieldKey && needsValue && (
            <Field label={fieldOp === "DUE_WITHIN_DAYS" ? "Days" : "Value"} hint={field?.fieldType === "DATE" && fieldOp === "EQUALS" ? "Dates as 2026-10-25" : undefined}>
              {(id) => (
                <Input
                  id={id}
                  type={fieldOp === "DUE_WITHIN_DAYS" || fieldOp === "GREATER_THAN" ? "number" : "text"}
                  required
                  maxLength={200}
                  value={fieldValue}
                  onChange={(e) => setFieldValue(e.target.value)}
                />
              )}
            </Field>
          )}
        </div>
        <p className="text-xs text-ink-faint">The condition is checked again just before each call, so someone who has since paid or been marked &ldquo;Do not call&rdquo; is skipped.</p>
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Call from (IST)" hint="Never earlier than the legal start set on the server.">
          {(id) => <Input id={id} type="time" value={windowStart} required onChange={(e) => setWindowStart(e.target.value)} />}
        </Field>
        <Field label="Call until (IST)" hint="Never later than the legal end set on the server.">
          {(id) => <Input id={id} type="time" value={windowEnd} required onChange={(e) => setWindowEnd(e.target.value)} />}
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
