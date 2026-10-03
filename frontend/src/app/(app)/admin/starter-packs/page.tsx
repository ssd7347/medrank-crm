"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, Loading, Modal, PageHeader } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useBranding } from "@/lib/branding";
import { useStages } from "@/lib/stages";
import type { PackApplied, StarterPack } from "@/lib/types";
import { useApi } from "@/lib/use-api";

/**
 * Industry starter packs: one click sets a new installation up for a kind of business. Loading a pack only
 * adds things, so it is safe on a CRM that is already in use.
 */
export default function StarterPacksPage() {
  const { data, error, loading } = useApi<StarterPack[]>("/api/settings/packs");
  const branding = useBranding();
  const stages = useStages();
  const [confirm, setConfirm] = useState<StarterPack | null>(null);
  const [result, setResult] = useState<{ pack: StarterPack; applied: PackApplied } | null>(null);
  const [busy, setBusy] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  async function apply(p: StarterPack) {
    setBusy(true);
    setApplyError(null);
    try {
      const applied = await api<PackApplied>(`/api/settings/packs/${p.id}/apply`, { method: "POST" });
      setResult({ pack: p, applied });
      setConfirm(null);
      branding.reload();
      stages.reload();
    } catch (e) {
      setApplyError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Industry starter packs" subtitle="Set the CRM up for a kind of business in one click, then adjust it to this customer." />
      {error && <Alert>{error}</Alert>}
      {result && (
        <div className="mb-4">
          <Alert tone="green">
            <strong>{result.pack.name}</strong> loaded: {result.applied.stagesAdded} stage(s), {result.applied.fieldsAdded} field(s),{" "}
            {result.applied.scriptsAdded} call script(s) and {result.applied.faqsAdded} answer(s) added. Next:{" "}
            {branding.has("AI_CALLS") ? (
              <>
                fill in the <Link href="/admin/faq" className="underline">[placeholders] in the answers</Link> and switch them on, then read and approve the{" "}
                <Link href="/voice/scripts" className="underline">call scripts</Link>.
              </>
            ) : (
              <>
                check the <Link href="/admin/fields" className="underline">custom fields</Link> and <Link href="/admin/stages" className="underline">stages</Link>.
              </>
            )}
          </Alert>
        </div>
      )}
      {loading && !data ? (
        <Loading />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {(data ?? []).map((p) => (
            <Card key={p.id}>
              <div className="flex h-full flex-col gap-3">
                <div className="flex items-start justify-between gap-2">
                  <h2 className="font-semibold text-ink">{p.name}</h2>
                  {branding.industryPack === p.id && <Badge tone="green">Loaded</Badge>}
                </div>
                <p className="text-sm text-ink-soft">{p.description}</p>
                <dl className="space-y-1.5 text-sm">
                  <Row label="Contacts called">{p.contactLabel}s</Row>
                  <Row label="Stages">{p.stages.join(", ")}</Row>
                  <Row label="Fields">{p.fields.join(", ")}</Row>
                  {branding.has("AI_CALLS") && (
                    <>
                      <Row label="Call scripts">{p.scripts.join(", ")}</Row>
                      <Row label="Answers">{p.faqs} starter answers</Row>
                    </>
                  )}
                </dl>
                <div className="mt-auto pt-2">
                  <Button variant={branding.industryPack === p.id ? "secondary" : "primary"} onClick={() => setConfirm(p)}>
                    {branding.industryPack === p.id ? "Load again" : "Load this pack"}
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
      {confirm && (
        <Modal open onClose={() => setConfirm(null)} title={`Load "${confirm.name}"?`}>
          <div className="space-y-3 text-sm text-ink-soft">
            {applyError && <Alert>{applyError}</Alert>}
            <p>This adds the pack&rsquo;s stages, fields{branding.has("AI_CALLS") ? ", call scripts and answers" : ""} and calls contacts &ldquo;{confirm.contactLabel}s&rdquo;.</p>
            <p>Nothing is deleted or overwritten: anything with the same name stays as it is.</p>
            {branding.has("AI_CALLS") && <p>Scripts arrive as drafts and answers switched off, so nothing reaches a caller until you have checked it.</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setConfirm(null)}>
                Cancel
              </Button>
              <Button loading={busy} onClick={() => apply(confirm)}>
                Load pack
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[7.5rem_1fr] gap-2">
      <dt className="text-ink-faint">{label}</dt>
      <dd className="text-ink">{children}</dd>
    </div>
  );
}
