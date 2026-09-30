"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Textarea } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { AgreementKind, AgreementTemplate } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

const KIND_LABEL: Record<AgreementKind, string> = { SERVICE_AGREEMENT: "Service agreement", DATA_CONSENT: "Data consent" };
const PLACEHOLDERS = ["{{orgName}}", "{{studentName}}", "{{parentName}}", "{{neetYear}}", "{{date}}"];

export default function AgreementTemplatesPage() {
  const { data, error, loading, reload } = useApi<AgreementTemplate[]>("/api/agreement-templates");
  const [editing, setEditing] = useState<AgreementTemplate | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="Agreement wording"
        subtitle="The text of the service agreement and consent form that counsellors issue to families."
        actions={<Button onClick={() => setEditing("new")}>Add document</Button>}
      />
      <div className="mb-4">
        <Alert tone="amber">
          The two starter documents are a plain-language draft, not legal advice. Have a lawyer review them before you use them with families. Editing here never
          changes a document that has already been issued or signed.
        </Alert>
      </div>
      <Card>
        {error && <Alert>{error}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No documents yet" />
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((t) => (
              <li key={t.id} className="flex items-start justify-between gap-3 py-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium">
                    {t.title}
                    {!t.active && (
                      <span className="ml-2 align-middle">
                        <Badge>Not in use</Badge>
                      </span>
                    )}
                  </p>
                  <p className="text-xs text-ink-soft">
                    {KIND_LABEL[t.kind]} · last changed {formatDate(t.updatedAt)} · {t.body.length.toLocaleString("en-IN")} characters
                  </p>
                </div>
                <Button size="sm" variant="secondary" onClick={() => setEditing(t)}>
                  Edit
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add document" : "Edit wording"} wide>
        {editing !== null && (
          <TemplateForm
            template={editing === "new" ? undefined : editing}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function TemplateForm({ template, onDone }: { template?: AgreementTemplate; onDone: () => void }) {
  const [v, setV] = useState({
    kind: (template?.kind ?? "SERVICE_AGREEMENT") as AgreementKind,
    title: template?.title ?? "",
    body: template?.body ?? "",
    active: template?.active ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      if (template) await api(`/api/agreement-templates/${template.id}`, { method: "PUT", body: v });
      else await api("/api/agreement-templates", { body: v });
      onDone();
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Type">
          {(id) => (
            <Select
              id={id}
              value={v.kind}
              onChange={(e) => setV({ ...v, kind: e.target.value as AgreementKind })}
              options={(Object.keys(KIND_LABEL) as AgreementKind[]).map((k) => ({ value: k, label: KIND_LABEL[k] }))}
            />
          )}
        </Field>
        <Field label="Title" required className="sm:col-span-2" error={fieldErrors.title}>
          {(id) => <Input id={id} required maxLength={200} value={v.title} onChange={(e) => setV({ ...v, title: e.target.value })} />}
        </Field>
      </div>
      <Field label="Text" required error={fieldErrors.body} hint={`Filled in automatically when issued: ${PLACEHOLDERS.join("  ")}`}>
        {(id) => <Textarea id={id} required rows={16} maxLength={20000} value={v.body} onChange={(e) => setV({ ...v, body: e.target.value })} className="font-mono text-xs" />}
      </Field>
      <Checkbox label="Counsellors can issue this" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
