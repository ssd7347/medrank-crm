"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { INDIAN_STATES } from "@/lib/format";
import type { Branch } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

export default function BranchesPage() {
  const { data, error, loading, reload } = useApi<Branch[]>("/api/branches");
  const [editing, setEditing] = useState<Branch | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="Branches"
        subtitle="Each office's leads and students are kept separate. You see all of them together."
        actions={<Button onClick={() => setEditing("new")}>Add branch</Button>}
      />
      <div className="mb-4">
        <Alert tone="blue">
          Staff who are given a branch (under Staff users) only see that branch&apos;s leads and students. Staff without a branch, and all admins, see every
          branch.
        </Alert>
      </div>
      <Card>
        <div className="-m-4">
          {error && (
            <div className="p-3">
              <Alert>{error}</Alert>
            </div>
          )}
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No branches yet">Add one if you work from more than one office. With no branches, everything belongs to head office.</EmptyState>
          ) : (
            <Table head={["Branch", "Code", "City", "State", "Status", ""]}>
              {data.map((b) => (
                <tr key={b.id}>
                  <Td className="font-medium">{b.name}</Td>
                  <Td className="tabular-nums">{b.code}</Td>
                  <Td>{b.city ?? "—"}</Td>
                  <Td>{b.state ?? "—"}</Td>
                  <Td>{b.active ? <Badge tone="green">Active</Badge> : <Badge>Closed</Badge>}</Td>
                  <Td className="text-right">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(b)}>
                      Edit
                    </Button>
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add branch" : "Edit branch"}>
        {editing !== null && (
          <BranchForm
            branch={editing === "new" ? undefined : editing}
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

function BranchForm({ branch, onDone }: { branch?: Branch; onDone: () => void }) {
  const [v, setV] = useState({
    name: branch?.name ?? "",
    code: branch?.code ?? "",
    city: branch?.city ?? "",
    state: branch?.state ?? "",
    active: branch?.active ?? true,
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
      if (branch) await api(`/api/branches/${branch.id}`, { method: "PUT", body: v });
      else await api("/api/branches", { body: v });
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
      <Field label="Branch name" required error={fieldErrors.name}>
        {(id) => <Input id={id} required maxLength={120} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />}
      </Field>
      <Field label="Short code" required error={fieldErrors.code} hint={branch ? "The code cannot be changed later" : "e.g. CHN or MDU. Used in reports; cannot be changed later."}>
        {(id) => <Input id={id} required disabled={!!branch} maxLength={20} value={v.code} onChange={(e) => setV({ ...v, code: e.target.value.toUpperCase() })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="City">{(id) => <Input id={id} maxLength={80} value={v.city} onChange={(e) => setV({ ...v, city: e.target.value })} />}</Field>
        <Field label="State">{(id) => <Select id={id} value={v.state} onChange={(e) => setV({ ...v, state: e.target.value })} options={INDIAN_STATES} placeholder="—" />}</Field>
      </div>
      <Checkbox label="Active" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
