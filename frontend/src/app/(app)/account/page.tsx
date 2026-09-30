"use client";

import { Button, Card, DescList, PageHeader } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { label } from "@/lib/format";

export default function AccountPage() {
  const { user, logout } = useAuth();

  if (!user) return null;
  return (
    <>
      <PageHeader title="My account" />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Profile">
          <DescList
            items={[
              ["Name", user.fullName],
              ["Mobile number (used to log in)", user.phone],
              ["Email", user.email],
              ["Role", label(user.role)],
              ["Branch", user.branch?.name ?? "Head office"],
            ]}
          />
          <p className="mt-4 text-xs text-ink-faint">Ask an admin to change your name, mobile number or role.</p>
        </Card>
        <Card title="Signing in">
          <p className="text-sm text-ink-soft">
            You log in with your mobile number and a one-time code, so there is no password to remember. You are signed out automatically after 3 minutes
            without activity.
          </p>
          <Button variant="secondary" className="mt-4" onClick={logout}>
            Log out
          </Button>
        </Card>
      </div>
    </>
  );
}
