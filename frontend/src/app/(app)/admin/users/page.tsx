"use client";

import { useState } from "react";

import { Alert, Badge, Button, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Card } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, label } from "@/lib/format";
import { ROLES, type Role, type User } from "@/lib/types";
import type { Branch } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

const ROLE_HELP: Record<Role, string> = {
  SUPER_ADMIN: "The one admin: everything, including creating staff IDs",
  COUNSELLOR: "Own leads and students; converts leads",
  TELECALLER: "Own leads and unassigned leads; no student profiles",
  DOCUMENTATION_EXEC: "Document checklists, uploads and verification",
  DATA_EXEC: "Proposes college / seat / cutoff changes",
  ACCOUNTANT: "Fee plans, payments, receipts and commissions",
  LOAN_DESK: "Reads student profiles",
  GRIEVANCE_OFFICER: "Grievance register and escalations",
};

export default function UsersPage() {
  const { user: me } = useAuth();
  const { data, error, loading, reload } = useApi<User[]>("/api/users");
  const [editing, setEditing] = useState<User | "new" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader title="Staff users" subtitle="You create every staff ID here. Each person then logs in with their own mobile number and a one-time code." actions={<Button onClick={() => setEditing("new")}>Add staff</Button>} />
      {notice && (
        <div className="mb-4">
          <Alert tone="green">{notice}</Alert>
        </div>
      )}
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
            <EmptyState title="No users" />
          ) : (
            <Table head={["Staff ID", "Name", "Mobile (login)", "Email", "Role", "Branch", "Status", "Since", ""]}>
              {data.map((u) => (
                <tr key={u.id}>
                  <Td className="font-mono text-xs">STF-{String(u.id).padStart(4, "0")}</Td>
                  <Td className="font-medium">
                    {u.fullName}
                    {u.id === me?.id && <span className="ml-2 text-xs text-ink-faint">(you)</span>}
                  </Td>
                  <Td className="tabular-nums">{u.phone ?? <span className="text-red-700">Missing: cannot log in</span>}</Td>
                  <Td>{u.email}</Td>
                  <Td>{label(u.role)}</Td>
                  <Td>{u.branch?.name ?? <span className="text-ink-faint">Head office</span>}</Td>
                  <Td>{u.active ? <Badge tone="green">Active</Badge> : <Badge>Deactivated</Badge>}</Td>
                  <Td className="whitespace-nowrap text-ink-soft">{formatDate(u.createdAt)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(u)}>
                      Edit
                    </Button>
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add staff" : "Edit staff"}>
        {editing !== null && (
          <UserForm
            user={editing === "new" ? undefined : editing}
            isSelf={editing !== "new" && editing.id === me?.id}
            onDone={(msg) => {
              setEditing(null);
              setNotice(msg);
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function UserForm({ user, isSelf, onDone }: { user?: User; isSelf: boolean; onDone: (msg: string) => void }) {
  const [v, setV] = useState({
    fullName: user?.fullName ?? "",
    email: user?.email ?? "",
    phone: user?.phone ?? "",
    role: (user?.role ?? "COUNSELLOR") as Role,
    active: user?.active ?? true,
    branchId: user?.branch?.id.toString() ?? "",
  });
  const branches = useApi<Branch[]>("/api/branches");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      if (user) {
        await api(`/api/users/${user.id}`, { method: "PUT", body: { fullName: v.fullName, phone: v.phone, role: v.role, active: v.active, branchId: v.branchId ? Number(v.branchId) : null } });
        onDone(`Saved ${v.fullName}.`);
      } else {
        await api("/api/users", { body: { fullName: v.fullName, email: v.email, phone: v.phone, role: v.role, branchId: v.branchId ? Number(v.branchId) : null } });
        onDone(`Created ${v.fullName}. They can now log in with ${v.phone}.`);
      }
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
      <Field label="Mobile number (used to log in)" required error={fieldErrors.phone} hint="10 digits. Each person needs their own number.">
        {(id) => <Input id={id} type="tel" inputMode="numeric" required value={v.phone} onChange={(e) => setV({ ...v, phone: e.target.value })} />}
      </Field>
      <Field label="Email" required error={fieldErrors.email}>
        {(id) => <Input id={id} type="email" required disabled={!!user} value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />}
      </Field>
      <Field label="Role" required hint={ROLE_HELP[v.role]}>
        {(id) => <Select id={id} disabled={isSelf} value={v.role} onChange={(e) => setV({ ...v, role: e.target.value as Role })} options={isSelf ? ROLES : ROLES.filter((r) => r !== "SUPER_ADMIN")} labelFor={label} />}
      </Field>
      {!!branches.data?.length && (
        <Field label="Branch" hint="Staff with a branch only see that branch's leads and students. Admins always see everything.">
          {(id) => (
            <Select
              id={id}
              value={v.branchId}
              onChange={(e) => setV({ ...v, branchId: e.target.value })}
              placeholder="Head office (all branches)"
              options={branches.data!.filter((b) => b.active || String(b.id) === v.branchId).map((b) => ({ value: String(b.id), label: b.name }))}
            />
          )}
        </Field>
      )}
      {user && !isSelf && <Checkbox label="Active (can log in)" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />}
      <Button type="submit" loading={saving}>
        {user ? "Save" : "Create ID"}
      </Button>
    </form>
  );
}
