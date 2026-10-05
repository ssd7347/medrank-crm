"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, ButtonLink, Card, Checkbox, Field, Input, Loading, PageHeader, Select, Table, Td } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatDateTime, label } from "@/lib/format";
import type { FieldDef, ImportBatch, ImportPreview, ImportResult, UserRef } from "@/lib/types";
import type { VoiceScript } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

const STANDARD: { value: string; label: string }[] = [
  { value: "name", label: "Name" },
  { value: "phone", label: "Phone" },
  { value: "alt_phone", label: "Alternate phone" },
  { value: "email", label: "Email" },
  { value: "notes", label: "Notes" },
  { value: "source", label: "Source" },
  { value: "tags", label: "Tags (comma-separated)" },
];
const NEW_FIELD: { value: string; label: string }[] = [
  { value: "new:TEXT", label: "New field: text" },
  { value: "new:NUMBER", label: "New field: number" },
  { value: "new:AMOUNT", label: "New field: amount (₹)" },
  { value: "new:DATE", label: "New field: date" },
];

/**
 * Bring in a customer's Excel or CSV file. Step 1 reads the headings and guesses what each column is; the
 * person corrects anything wrong, and every column that is not a standard one becomes a custom field.
 */
export default function ImportPage() {
  const { hasRole } = useAuth();
  const { plural, has } = useBranding();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [updateExisting, setUpdateExisting] = useState(true);
  const [assignToId, setAssignToId] = useState("");
  const [consent, setConsent] = useState(false);
  const [recording, setRecording] = useState(false);
  const [evidence, setEvidence] = useState("");
  const [callNow, setCallNow] = useState(true);
  const [callScript, setCallScript] = useState("");
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fields = useApi<FieldDef[]>("/api/fields");
  const staff = useApi<UserRef[]>("/api/users/assignable");
  const history = useApi<ImportBatch[]>(hasRole(...MANAGER_ROLES) ? "/api/contacts/imports" : null);
  // Starting AI calls is the admin's decision, and only with a script the admin has approved.
  const canCall = hasRole("SUPER_ADMIN") && has("AI_CALLS");
  const scripts = useApi<VoiceScript[]>(canCall ? "/api/voice/scripts" : null);
  const approvedScripts = [...new Set((scripts.data ?? []).filter((s) => s.kind === "OUTBOUND" && s.approved).map((s) => s.name))];
  const script = callScript || approvedScripts[0] || "";
  const willCall = canCall && callNow && script !== "";

  if (!hasRole(...MANAGER_ROLES)) return <Alert>Only an admin or a manager can import.</Alert>;

  const targets = [
    ...STANDARD,
    ...(fields.data ?? []).map((f) => ({ value: `field:${f.key}`, label: `Field: ${f.label}` })),
    ...NEW_FIELD,
    { value: "ignore", label: "Do not import" },
  ];
  const chosen = Object.values(mapping);
  const hasName = chosen.includes("name");
  const hasPhone = chosen.includes("phone");
  const duplicates = STANDARD.map((s) => s.value).filter((v) => chosen.filter((c) => c === v).length > 1);

  async function readFile(f: File) {
    setFile(f);
    setPreview(null);
    setResult(null);
    setError(null);
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", f);
      const p = await api<ImportPreview>("/api/contacts/import/preview", { form });
      setPreview(p);
      setMapping(p.suggested);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function runImport() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const options = {
        mapping,
        updateExisting,
        assignToId: assignToId ? Number(assignToId) : null,
        aiCallConsent: consent,
        recordingConsent: consent && recording,
        consentEvidence: consent ? evidence : null,
        callScriptName: willCall ? script : null,
      };
      form.append("options", new Blob([JSON.stringify(options)], { type: "application/json" }));
      setResult(await api<ImportResult>("/api/contacts/import", { form }));
      setPreview(null);
      history.reload();
      fields.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Import from Excel" subtitle={`Add or update ${plural.toLowerCase()} from an .xlsx, .xls or .csv file. The first row must hold the column headings.`} />
      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}

      {result ? (
        <Card title="Import finished">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Count label="Rows in the file" value={result.totalRows} />
            <Count label="Added" value={result.created} tone="green" />
            <Count label="Updated" value={result.updated} />
            <Count label="Skipped" value={result.skipped} tone={result.skipped ? "amber" : undefined} />
          </div>
          {result.calls && <CallsSummary calls={result.calls} />}
          {result.newFields.length > 0 && (
            <p className="mt-4 text-sm">
              New fields created: {result.newFields.join(", ")}.{" "}
              <Link href="/admin/fields" className="text-brand-700 hover:underline">
                Decide what the AI agent may say about them
              </Link>
              .
            </p>
          )}
          {result.problems.length > 0 && (
            <div className="mt-4">
              <p className="mb-1 text-sm font-medium">Rows that need a look</p>
              <ul className="max-h-64 space-y-0.5 overflow-y-auto rounded-lg bg-muted p-3 text-xs">
                {result.problems.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="mt-5 flex flex-wrap gap-2">
            <ButtonLink href={`/contacts?importBatchId=${result.batchId}`}>See who was imported</ButtonLink>
            <Button variant="secondary" onClick={() => (setResult(null), setFile(null))}>
              Import another file
            </Button>
          </div>
        </Card>
      ) : !preview ? (
        <Card title="1. Choose the file">
          <label className="flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-line px-4 py-10 text-center hover:bg-muted">
            <span className="text-sm font-medium">{busy ? "Reading the file…" : file ? file.name : "Click to choose an Excel or CSV file"}</span>
            <span className="text-xs text-ink-faint">Up to 20,000 rows and 10 MB. Any columns: name and phone are the only ones required.</span>
            <input
              type="file"
              accept=".xlsx,.xls,.csv,text/csv,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              className="sr-only"
              disabled={busy}
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) readFile(f);
                e.target.value = "";
              }}
            />
          </label>
          {busy && <Loading />}
        </Card>
      ) : (
        <div className="space-y-6">
          <Card title={`2. Check the columns (${preview.totalRows} rows in ${preview.fileName})`}>
            <p className="mb-3 text-sm text-ink-soft">We guessed what each column holds. Change anything that is wrong; columns that are not a standard one become custom fields.</p>
            <div className="-mx-4">
              <Table head={["Column in your file", "Example values", "Import as"]}>
                {preview.headers.map((h, i) => (
                  <tr key={h}>
                    <Td className="font-medium">{h}</Td>
                    <Td className="text-ink-soft">
                      <span className="block max-w-[16rem] truncate">
                        {preview.sample
                          .map((r) => r[i])
                          .filter(Boolean)
                          .slice(0, 3)
                          .join(" · ") || "—"}
                      </span>
                    </Td>
                    <Td>
                      <Select aria-label={`Import ${h} as`} value={mapping[h] ?? "ignore"} options={targets} onChange={(e) => setMapping((m) => ({ ...m, [h]: e.target.value }))} />
                    </Td>
                  </tr>
                ))}
              </Table>
            </div>
            {(!hasName || !hasPhone) && (
              <div className="mt-3">
                <Alert tone="amber">Choose the column that holds the {!hasName ? "name" : "phone number"}.</Alert>
              </div>
            )}
            {duplicates.length > 0 && (
              <div className="mt-3">
                <Alert tone="amber">Two columns are set to the same thing: {duplicates.map((d) => STANDARD.find((s) => s.value === d)?.label).join(", ")}.</Alert>
              </div>
            )}
          </Card>

          <Card title="3. Options">
            <div className="space-y-4">
              <Checkbox label="If a phone number is already in the CRM, update that record" checked={updateExisting} onChange={(e) => setUpdateExisting(e.target.checked)} />
              <Field label="Assign everyone in this file to" hint="Leave empty to keep them unassigned (or as they are).">
                {(id) => (
                  <Select
                    id={id}
                    value={assignToId}
                    placeholder="Nobody"
                    options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: `${u.fullName} (${label(u.role)})` }))}
                    onChange={(e) => setAssignToId(e.target.value)}
                  />
                )}
              </Field>
              {has("AI_CALLS") && (
              <div className="rounded-lg border border-line p-3">
                <Checkbox label="Everyone in this file agreed to AI-assisted calls" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
                {consent && (
                  <div className="mt-3 space-y-3 pl-6">
                    <Checkbox label="They also agreed to calls being recorded" checked={recording} onChange={(e) => setRecording(e.target.checked)} />
                    <Field label="How did they agree?" required hint="For example: the sign-up form they filled in, with its wording. This is kept as proof.">
                      {(id) => <Input id={id} maxLength={400} value={evidence} onChange={(e) => setEvidence(e.target.value)} />}
                    </Field>
                  </div>
                )}
                {!consent && <p className="mt-1 pl-6 text-xs text-ink-faint">Without consent the AI agent will not call them. You can record consent later, person by person.</p>}
              </div>
              )}
              {canCall && (
                <div className="rounded-lg border border-line p-3">
                  {scripts.loading && !scripts.data ? (
                    <Loading />
                  ) : approvedScripts.length === 0 ? (
                    <p className="text-sm text-ink-soft">
                      To have the AI agent call everyone in this file straight after the import,{" "}
                      <Link href="/voice/scripts" className="text-brand-700 hover:underline">
                        approve a call script
                      </Link>{" "}
                      first.
                    </p>
                  ) : (
                    <>
                      <Checkbox label="Call everyone in this file with the AI agent as soon as the import finishes" checked={callNow} onChange={(e) => setCallNow(e.target.checked)} />
                      {callNow && (
                        <div className="mt-3 space-y-3 pl-6">
                          <Field label="Call script" hint="What the AI agent calls them about.">
                            {(id) => <Select id={id} value={script} options={approvedScripts} onChange={(e) => setCallScript(e.target.value)} />}
                          </Field>
                          <p className="text-xs text-ink-faint">
                            Calls run as a campaign you can pause at any time. They are made only within calling hours, never to anyone marked &ldquo;Do not call&rdquo;, and only to people who agreed to AI calls.
                          </p>
                          {!consent && (
                            <Alert tone="amber">
                              Only people who agreed to AI calls earlier will be called. If everyone in this file agreed, tick the consent box above.
                            </Alert>
                          )}
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
          </Card>

          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" onClick={() => (setPreview(null), setFile(null))}>
              Choose a different file
            </Button>
            <Button loading={busy} disabled={!hasName || !hasPhone || duplicates.length > 0 || (consent && !evidence.trim())} onClick={runImport}>
              Import {preview.totalRows} {preview.totalRows === 1 ? "row" : "rows"}{willCall ? " and start calling" : ""}
            </Button>
          </div>
        </div>
      )}

      {history.data && history.data.length > 0 && !preview && (
        <Card title="Earlier uploads" className="mt-6">
          <div className="-m-4">
            <Table head={["File", "When", "Added", "Updated", "Skipped", ""]}>
              {history.data.map((b) => (
                <tr key={b.id}>
                  <Td className="font-medium break-all">{b.fileName}</Td>
                  <Td className="whitespace-nowrap">{formatDateTime(b.createdAt)}</Td>
                  <Td>{b.created}</Td>
                  <Td>{b.updated}</Td>
                  <Td>{b.skipped ? <Badge tone="amber">{b.skipped}</Badge> : 0}</Td>
                  <Td className="text-right">
                    <Link href={`/contacts?importBatchId=${b.id}`} className="text-sm font-medium text-brand-800 hover:underline">
                      Show
                    </Link>
                  </Td>
                </tr>
              ))}
            </Table>
          </div>
        </Card>
      )}
    </>
  );
}

function Count({ label: text, value, tone }: { label: string; value: number; tone?: "green" | "amber" }) {
  return (
    <div className="rounded-lg border border-line p-3">
      <p className="eyebrow text-ink-faint">{text}</p>
      <p className={`mt-0.5 text-xl font-semibold tabular-nums ${tone === "green" ? "text-emerald-700" : tone === "amber" ? "text-amber-700" : ""}`}>{value}</p>
    </div>
  );
}

function CallsSummary({ calls }: { calls: NonNullable<ImportResult["calls"]> }) {
  if (!calls.campaignId || calls.people === 0) {
    return (
      <div className="mt-4">
        <Alert tone="amber">Nobody in this file can be called (no one was added or updated, or everyone is marked &ldquo;Do not call&rdquo;).</Alert>
      </div>
    );
  }
  return (
    <div className="mt-4 rounded-lg border border-line p-3">
      <p className="text-sm font-medium">The AI agent is calling them</p>
      <div className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Count label="In the campaign" value={calls.people} />
        <Count label="Being called now" value={calls.callingNow} tone="green" />
        <Count label="Called later" value={calls.waiting} />
        <Count label="Will not be called" value={calls.notAllowed} tone={calls.notAllowed ? "amber" : undefined} />
      </div>
      <p className="mt-2 text-xs text-ink-faint">
        &ldquo;Called later&rdquo; is usually outside calling hours. &ldquo;Will not be called&rdquo; is usually no consent to AI calls; record it on each
        person&rsquo;s page if they agreed. Calls start within a minute.
      </p>
      <div className="mt-3">
        <ButtonLink href={`/voice/campaigns/${calls.campaignId}`} variant="secondary">
          Follow the calls
        </ButtonLink>
      </div>
    </div>
  );
}
