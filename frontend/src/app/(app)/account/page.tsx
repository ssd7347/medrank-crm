"use client";

import { useState } from "react";

import { Alert, Button, Card, DescList, Field, Input, PageHeader } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { label } from "@/lib/format";

export default function AccountPage() {
  const { user, logout } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function change(e: React.FormEvent) {
    e.preventDefault();
    if (next !== confirm) {
      setError("The new passwords do not match.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api("/api/auth/change-password", { body: { currentPassword: current, newPassword: next } });
      // The backend ends every session on a password change, so log in again.
      await logout();
    } catch (err) {
      setError(errorMessage(err));
      setSaving(false);
    }
  }

  if (!user) return null;
  return (
    <>
      <PageHeader title="My account" />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Profile">
          <DescList
            items={[
              ["Name", user.fullName],
              ["Email", user.email],
              ["Phone", user.phone],
              ["Role", label(user.role)],
            ]}
          />
          <p className="mt-4 text-xs text-ink-faint">Ask an admin to change your name, phone or role.</p>
        </Card>
        <Card title="Change password">
          <form onSubmit={change} className="space-y-4">
            {error && <Alert>{error}</Alert>}
            <Field label="Current password" required>
              {(id) => <Input id={id} type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />}
            </Field>
            <Field label="New password" required hint="At least 10 characters">
              {(id) => <Input id={id} type="password" autoComplete="new-password" required minLength={10} maxLength={72} value={next} onChange={(e) => setNext(e.target.value)} />}
            </Field>
            <Field label="Confirm new password" required>
              {(id) => <Input id={id} type="password" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
            </Field>
            <Button type="submit" loading={saving}>
              Change password
            </Button>
            <p className="text-xs text-ink-faint">You will be logged out of all devices and asked to log in again.</p>
          </form>
        </Card>
      </div>
    </>
  );
}
