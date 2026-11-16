"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import { AGENT_ACCESS, AGENT_ACCESS_LABEL, FIELD_TYPES, type AgentAccess, type FieldDef, type FieldType } from "@/lib/types";
import { useApi } from "@/lib/use-api";

/**
 * The extra columns kept about each contact. Most are created by an Excel import; here the admin names them,
 * sets their type, and decides what the AI agent may say about each one.
 */
export default function FieldsPage() {
  const { data, error, loading, reload } = useApi<FieldDef[]>("/api/fields");
  const [editing, setEditing] = useState<Partial<FieldDef> | null>(null);

  return (
    <>
      <PageHeader
        title="Custom fields"
        subtitle="Extra details kept about each contact, such as an amount due, a due date or a customer ID."
        actions={<Button onClick={() => setEditing({ fieldType: "TEXT", agentAccess: "AFTER_VERIFY", verifyWith: false, sortOrder: 100, active: true })}>New field</Button>}
      />
      <div className="mb-4">
        <Alert tone="blue">
          <strong>What the AI may say.</strong> &ldquo;Only after the caller is verified&rdquo; is the safe choice for anything private (amounts,
          dates, account details). Tick &ldquo;Ask to confirm&rdquo; on a field the caller would know, such as a customer ID, so the agent can check
          who it is talking to.
        </Alert>
      </div>
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No custom fields yet">They are created for you when you import an Excel file, or add one here.</EmptyState>
          ) : (
            <Table head={["Field", "Type", "The AI may say it", "Ask to confirm", ""]}>
              {data.map((f) => (
                <tr key={f.id}>
                  <Td>
                    <span className="font-medium">{f.label}</span>
                    {!f.active && (
                      <span className="ml-2">
                        <Badge>Hidden</Badge>
                      </span>
                    )}
                    <span className="block font-mono text-xs text-ink-faint">{f.key}</span>
                  </Td>
                  <Td>{label(f.fieldType)}</Td>
                  <Td>
                    <Badge tone={f.agentAccess === "NONE" ? "gray" : f.agentAccess === "ANYONE" ? "amber" : "green"}>{AGENT_ACCESS_LABEL[f.agentAccess]}</Badge>
                  </Td>
                  <Td>{f.verifyWith ? "Yes" : "—"}</Td>
                  <Td className="text-right">
                    <Button size="sm" variant="secondary" onClick={() => setEditing(f)}>
                      Edit
                    </Button>
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing?.id ? "Edit field" : "New field"}>
        {editing && (
          <FieldForm
            initial={editing}
            onSaved={() => {
              setEditing(null);
              reload();
            }}
            onCancel={() => setEditing(null)}
          />
        )}
      </Modal>
    </>
  );
}

function FieldForm({ initial, onSaved, onCancel }: { initial: Partial<FieldDef>; onSaved: () => void; onCancel: () => void }) {
  const [name, setName] = useState(initial.label ?? "");
  const [type, setType] = useState<FieldType>(initial.fieldType ?? "TEXT");
  const [access, setAccess] = useState<AgentAccess>(initial.agentAccess ?? "AFTER_VERIFY");
  const [verify, setVerify] = useState(initial.verifyWith ?? false);
  const [order, setOrder] = useState(String(initial.sortOrder ?? 100));
  const [active, setActive] = useState(initial.active ?? true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body = { label: name, fieldType: type, agentAccess: access, verifyWith: verify, sortOrder: Number(order) || 0, active };
    try {
      if (initial.id) await api(`/api/fields/${initial.id}`, { method: "PUT", body });
      else await api("/api/fields", { body });
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <Field label="Name" required hint={initial.key ? `Stored as ${initial.key}; renaming keeps the values.` : undefined}>
        {(id) => <Input id={id} required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Type">{(id) => <Select id={id} value={type} options={[...FIELD_TYPES]} labelFor={label} onChange={(e) => setType(e.target.value as FieldType)} />}</Field>
        <Field label="Order" hint="Lower comes first">
          {(id) => <Input id={id} type="number" min={0} max={10000} value={order} onChange={(e) => setOrder(e.target.value)} />}
        </Field>
      </div>
      <Field label="The AI agent may say it">
        {(id) => (
          <Select id={id} value={access} options={AGENT_ACCESS.map((a) => ({ value: a, label: AGENT_ACCESS_LABEL[a] }))} onChange={(e) => setAccess(e.target.value as AgentAccess)} />
        )}
      </Field>
      <Checkbox label="Ask the caller to confirm this to prove who they are" checked={verify} onChange={(e) => setVerify(e.target.checked)} />
      {verify && (
        <p className="-mt-2 text-xs text-ink-faint">
          The AI asks for it by this name before it knows who answered, so the name should not say why you are calling: &ldquo;Account number&rdquo;, not
          &ldquo;Loan account number&rdquo;.
        </p>
      )}
      <Checkbox label="Show this field" checked={active} onChange={(e) => setActive(e.target.checked)} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
