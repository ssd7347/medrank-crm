"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import { APPLIES_WHEN, type DocType } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

/** Configurable document checklist (spec 4.7): which documents each kind of student needs. */
export default function DocumentTypesPage() {
  const { data, error, loading, reload } = useApi<DocType[]>("/api/document-types");
  const [editing, setEditing] = useState<DocType | "new" | null>(null);

  return (
    <>
      <PageHeader title="Document checklist" subtitle="Which certificates each student needs. Changes apply to every student's checklist immediately." actions={<Button onClick={() => setEditing("new")}>Add document</Button>} />
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No document types" />
          ) : (
            <Table head={["Document", "Required for", "Expiry date", "Order", "Status", ""]}>
              {data.map((t) => (
                <tr key={t.id}>
                  <Td>
                    <span className="font-medium">{t.name}</span>
                    <span className="block font-mono text-xs text-ink-faint">{t.code}</span>
                  </Td>
                  <Td>{label(t.appliesWhen)}</Td>
                  <Td>{t.requiresExpiry ? "Tracked" : "—"}</Td>
                  <Td className="tabular-nums">{t.sortOrder}</Td>
                  <Td>{t.active ? <Badge tone="green">Active</Badge> : <Badge>Hidden</Badge>}</Td>
                  <Td className="text-right">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(t)}>
                      Edit
                    </Button>
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add document" : "Edit document"}>
        {editing !== null && (
          <TypeForm
            type={editing === "new" ? undefined : editing}
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

function TypeForm({ type, onDone }: { type?: DocType; onDone: () => void }) {
  const [v, setV] = useState({
    code: type?.code ?? "",
    name: type?.name ?? "",
    appliesWhen: type?.appliesWhen ?? "ALWAYS",
    requiresExpiry: type?.requiresExpiry ?? false,
    sortOrder: String(type?.sortOrder ?? 200),
    active: type?.active ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          const body = { ...v, sortOrder: Number(v.sortOrder) };
          if (type) await api(`/api/document-types/${type.id}`, { method: "PUT", body });
          else await api("/api/document-types", { body });
          onDone();
        } catch (err) {
          if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="Name" required>{(id) => <Input id={id} required maxLength={120} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="Code" required hint="Short identifier, e.g. INCOME_CERT" error={fieldErrors.code}>
        {(id) => <Input id={id} required maxLength={40} value={v.code} onChange={(e) => setV({ ...v, code: e.target.value })} />}
      </Field>
      <Field label="Required for">{(id) => <Select id={id} value={v.appliesWhen} onChange={(e) => setV({ ...v, appliesWhen: e.target.value as DocType["appliesWhen"] })} options={APPLIES_WHEN} labelFor={label} />}</Field>
      <Field label="Display order">{(id) => <Input id={id} type="number" min={0} max={10000} value={v.sortOrder} onChange={(e) => setV({ ...v, sortOrder: e.target.value })} />}</Field>
      <Checkbox label="Track an expiry / validity date" checked={v.requiresExpiry} onChange={(e) => setV({ ...v, requiresExpiry: e.target.checked })} />
      <Checkbox label="Active" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
