"use client";

import { useState } from "react";

import { Alert, Badge, Button, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Table, Td, Card } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, label } from "@/lib/format";
import { ROLES, type Role, type User } from "@/lib/types";
import { useApi } from "@/lib/use-api";

const ROLE_HELP: Record<Role, string> = {
  SUPER_ADMIN: "Everything, including staff accounts and data approvals",
  COUNSELLOR: "Own leads and students; converts leads",
  TELECALLER: "Own leads and unassigned leads; no student profiles",
  DOCUMENTATION_EXEC: "Reads student profiles",
  DATA_EXEC: "Proposes college / seat / cutoff changes",
  ACCOUNTANT: "Reads student profiles",
  LOAN_DESK: "Reads student profiles",
  GRIEVANCE_OFFICER: "Reads student profiles",
};

export default function UsersPage() {
  const { user: me } = useAuth();
  const { data, error, loading, reload } = useApi<User[]>("/api/users");
  const [editing, setEditing] = useState<User | "new" | null>(null);
  const [resetting, setResetting] = useState<User | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader title="Staff users" subtitle="Each person gets their own login; never share accounts." actions={<Button onClick={() => setEditing("new")}>Add staff</Button>} />
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
            <Table head={["Name", "Email", "Phone", "Role", "Status", "Since", ""]}>
              {data.map((u) => (
                <tr key={u.id}>
                  <Td className="font-medium">
                    {u.fullName}
                    {u.id === me?.id && <span className="ml-2 text-xs text-ink-faint">(you)</span>}
                  </Td>
                  <Td>{u.email}</Td>
                  <Td className="tabular-nums">{u.phone ?? "—"}</Td>
                  <Td>{label(u.role)}</Td>
                  <Td>{u.active ? <Badge tone="green">Active</Badge> : <Badge>Deactivated</Badge>}</Td>
                  <Td className="whitespace-nowrap text-ink-soft">{formatDate(u.createdAt)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(u)}>
                      Edit
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setResetting(u)}>
                      Reset password
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
      <Modal open={resetting !== null} onClose={() => setResetting(null)} title={`Reset password · ${resetting?.fullName ?? ""}`}>
        {resetting && (
          <ResetForm
            user={resetting}
            onDone={() => {
              setResetting(null);
              setNotice(`Password reset for ${resetting.fullName}. They have been logged out everywhere.`);
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
    password: "",
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
      if (user) {
        await api(`/api/users/${user.id}`, { method: "PUT", body: { fullName: v.fullName, phone: v.phone, role: v.role, active: v.active } });
        onDone(`Saved ${v.fullName}.`);
      } else {
        await api("/api/users", { body: { fullName: v.fullName, email: v.email, phone: v.phone, role: v.role, password: v.password } });
        onDone(`Created ${v.fullName}. Share the password with them privately and ask them to change it after first login.`);
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
      <Field label="Email (login)" required error={fieldErrors.email}>
        {(id) => <Input id={id} type="email" required disabled={!!user} value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />}
      </Field>
      <Field label="Phone" error={fieldErrors.phone}>
        {(id) => <Input id={id} type="tel" value={v.phone} onChange={(e) => setV({ ...v, phone: e.target.value })} />}
      </Field>
      <Field label="Role" required hint={ROLE_HELP[v.role]}>
        {(id) => <Select id={id} disabled={isSelf} value={v.role} onChange={(e) => setV({ ...v, role: e.target.value as Role })} options={ROLES} labelFor={label} />}
      </Field>
      {!user && (
        <Field label="Initial password" required hint="At least 10 characters" error={fieldErrors.password}>
          {(id) => <Input id={id} type="password" autoComplete="new-password" required minLength={10} maxLength={72} value={v.password} onChange={(e) => setV({ ...v, password: e.target.value })} />}
        </Field>
      )}
      {user && !isSelf && <Checkbox label="Active (can log in)" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />}
      <Button type="submit" loading={saving}>
        {user ? "Save" : "Create account"}
      </Button>
    </form>
  );
}

function ResetForm({ user, onDone }: { user: User; onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          await api(`/api/users/${user.id}/reset-password`, { body: { password } });
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
      <Field label="New password" required hint="At least 10 characters. This logs them out of every device.">
        {(id) => <Input id={id} type="password" autoComplete="new-password" required minLength={10} maxLength={72} value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Button type="submit" loading={saving}>
        Reset password
      </Button>
    </form>
  );
}
