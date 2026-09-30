"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import type { Agreement, AgreementTemplate } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

import { Alert, Badge, Button, ButtonLink, Card, EmptyState, Field, Input, Loading, Modal, Select, type Tone } from "./ui";

export const AGREEMENT_TONE: Record<Agreement["status"], Tone> = { PENDING: "amber", SIGNED: "green", CANCELLED: "gray" };
export const SIGN_METHOD_LABEL: Record<NonNullable<Agreement["signMethod"]>, string> = {
  PORTAL_ACCEPTANCE: "accepted in the portal",
  PAPER: "signed on paper",
  AADHAAR_ESIGN: "Aadhaar e-Sign",
};

export function AgreementsTab({ studentId }: { studentId: number }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data, error, loading, reload } = useApi<Agreement[]>(`/api/students/${studentId}/agreements`);
  const templates = useApi<AgreementTemplate[]>(canEdit ? "/api/agreement-templates" : null);
  const [templateId, setTemplateId] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [paper, setPaper] = useState<Agreement | null>(null);
  const options = (templates.data ?? []).filter((t) => t.active);

  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    setActionError(null);
    try {
      await fn();
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <Alert tone="blue">
        Aadhaar e-Sign is not connected. The family can read and accept an agreement in their portal (recorded with their name, time and login), or you can
        record that a signed paper copy is on file.
      </Alert>
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      <Card title="Agreements & consent">
        {canEdit && options.length > 0 && (
          <div className="mb-4 flex flex-wrap items-end gap-2 border-b border-line pb-4">
            <Field label="Send for signing" className="min-w-0 flex-1 sm:max-w-sm">
              {(id) => <Select id={id} value={templateId} onChange={(e) => setTemplateId(e.target.value)} placeholder="Choose a document…" options={options.map((t) => ({ value: String(t.id), label: t.title }))} />}
            </Field>
            <Button
              disabled={!templateId}
              loading={busy}
              onClick={() =>
                run(async () => {
                  await api(`/api/students/${studentId}/agreements`, { body: { templateId: Number(templateId) } });
                  setTemplateId("");
                })
              }
            >
              Issue
            </Button>
          </div>
        )}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="Nothing issued yet">Issue the service agreement and the data-consent form when the student enrols.</EmptyState>
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((a) => (
              <li key={a.id} className="py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{a.title}</span>
                  <Badge tone={AGREEMENT_TONE[a.status]}>{a.status === "PENDING" ? "Waiting for signature" : a.status === "SIGNED" ? "Signed" : "Cancelled"}</Badge>
                </div>
                <p className="mt-0.5 text-xs text-ink-soft">
                  Issued {formatDateTime(a.issuedAt)}
                  {a.status === "SIGNED" && a.signMethod && ` · ${SIGN_METHOD_LABEL[a.signMethod]} by ${a.signerName} on ${formatDateTime(a.signedAt)}`}
                </p>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  <ButtonLink href={`/agreements/${a.id}`} variant="secondary">
                    View / print
                  </ButtonLink>
                  {canEdit && a.status === "PENDING" && (
                    <>
                      <Button variant="secondary" onClick={() => setPaper(a)}>
                        Signed on paper
                      </Button>
                      <Button
                        variant="ghost"
                        onClick={() => {
                          if (confirm("Cancel this unsigned document?")) run(() => api(`/api/agreements/${a.id}/cancel`, { method: "POST" }));
                        }}
                      >
                        Cancel
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Modal open={paper !== null} onClose={() => setPaper(null)} title="Record a paper signature">
        {paper && (
          <PaperForm
            agreement={paper}
            onDone={() => {
              setPaper(null);
              reload();
            }}
          />
        )}
      </Modal>
    </div>
  );
}

function PaperForm({ agreement, onDone }: { agreement: Agreement; onDone: () => void }) {
  const [signerName, setSignerName] = useState("");
  const [signerRelation, setSignerRelation] = useState("PARENT");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await api(`/api/agreements/${agreement.id}/paper`, { body: { signerName, signerRelation } });
          onDone();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Alert tone="amber">Do this only when you hold the printed “{agreement.title}” with their signature. It cannot be undone.</Alert>
      <Field label="Who signed" required>
        {(id) => <Input id={id} required maxLength={120} value={signerName} onChange={(e) => setSignerName(e.target.value)} />}
      </Field>
      <Field label="They are the">
        {(id) => (
          <Select
            id={id}
            value={signerRelation}
            onChange={(e) => setSignerRelation(e.target.value)}
            options={[
              { value: "STUDENT", label: "Student" },
              { value: "PARENT", label: "Parent" },
              { value: "GUARDIAN", label: "Guardian" },
            ]}
          />
        )}
      </Field>
      <Button type="submit" loading={saving}>
        Record as signed
      </Button>
    </form>
  );
}
