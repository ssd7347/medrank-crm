"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Table, Td } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import type { Associate } from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function AssociatesPage() {
  const { data, error, loading, reload } = useApi<Associate[]>("/api/referral-associates");
  const [editing, setEditing] = useState<Associate | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="Referral associates"
        subtitle="District-level partners who refer students. Referral leads are credited to them."
        actions={<Button onClick={() => setEditing("new")}>Add associate</Button>}
      />
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
            <EmptyState title="No associates yet" />
          ) : (
            <Table head={["Name", "Phone", "District", "Commission", "Status", ""]}>
              {data.map((a) => (
                <tr key={a.id}>
                  <Td className="font-medium">{a.fullName}</Td>
                  <Td className="tabular-nums">{a.phone}</Td>
                  <Td>{a.district ?? "—"}</Td>
                  <Td className="tabular-nums">{a.commissionRate}%</Td>
                  <Td>{a.active ? <Badge tone="green">Active</Badge> : <Badge>Inactive</Badge>}</Td>
                  <Td className="text-right">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(a)}>
                      Edit
                    </Button>
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add associate" : "Edit associate"}>
        {editing !== null && (
          <AssociateForm
            associate={editing === "new" ? undefined : editing}
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

function AssociateForm({ associate, onDone }: { associate?: Associate; onDone: () => void }) {
  const [v, setV] = useState({
    fullName: associate?.fullName ?? "",
    phone: associate?.phone ?? "",
    district: associate?.district ?? "",
    commissionRate: associate?.commissionRate?.toString() ?? "0",
    active: associate?.active ?? true,
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
      const body = { ...v, commissionRate: Number(v.commissionRate) };
      if (associate) await api(`/api/referral-associates/${associate.id}`, { method: "PUT", body });
      else await api("/api/referral-associates", { body });
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
      <Field label="Full name" required error={fieldErrors.fullName}>
        {(id) => <Input id={id} required maxLength={120} value={v.fullName} onChange={(e) => setV({ ...v, fullName: e.target.value })} />}
      </Field>
      <Field label="Phone" required error={fieldErrors.phone}>
        {(id) => <Input id={id} type="tel" required value={v.phone} onChange={(e) => setV({ ...v, phone: e.target.value })} />}
      </Field>
      <Field label="District">{(id) => <Input id={id} maxLength={80} value={v.district} onChange={(e) => setV({ ...v, district: e.target.value })} />}</Field>
      <Field label="Commission rate (%)" required error={fieldErrors.commissionRate}>
        {(id) => <Input id={id} type="number" required min={0} max={100} step="0.01" value={v.commissionRate} onChange={(e) => setV({ ...v, commissionRate: e.target.value })} />}
      </Field>
      <Checkbox label="Active" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
