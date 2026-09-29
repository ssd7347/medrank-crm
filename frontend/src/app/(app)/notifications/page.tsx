"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, EmptyState, Loading, PageHeader, cx } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { AppNotification } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";

export default function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const { data, error, loading, reload } = useApi<AppNotification[]>("/api/notifications", { unreadOnly, limit: 100 });
  const [actionError, setActionError] = useState<string | null>(null);

  async function read(n: AppNotification) {
    if (n.readAt) return;
    try {
      await api(`/api/notifications/${n.id}/read`, { method: "POST" });
      window.dispatchEvent(new Event("notifications-changed"));
      reload();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        actions={
          <>
            <Button variant="secondary" onClick={() => setUnreadOnly((u) => !u)}>
              {unreadOnly ? "Show all" : "Unread only"}
            </Button>
            <Button
              variant="secondary"
              onClick={async () => {
                await api("/api/notifications/read-all", { method: "POST" });
                window.dispatchEvent(new Event("notifications-changed"));
                reload();
              }}
            >
              Mark all read
            </Button>
          </>
        }
      />
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      <Card>
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title={unreadOnly ? "No unread notifications" : "No notifications yet"} />
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((n) => {
              const body = (
                <div className={cx("flex gap-3 py-3", n.readAt && "opacity-60")}>
                  <span className={cx("mt-1.5 h-2 w-2 shrink-0 rounded-full", n.readAt ? "bg-transparent" : n.priority === "URGENT" ? "bg-red-600" : "bg-brand-500")} aria-hidden />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      {n.priority === "URGENT" && <Badge tone="red">Urgent</Badge>}
                      <span className="font-medium">{n.title}</span>
                    </div>
                    {n.body && <p className="mt-0.5 text-sm text-ink-soft">{n.body}</p>}
                    <p className="mt-0.5 text-xs text-ink-faint">{formatDateTime(n.createdAt)}</p>
                  </div>
                </div>
              );
              return (
                <li key={n.id}>
                  {n.link ? (
                    <Link href={n.link} onClick={() => read(n)} className="block rounded-lg hover:bg-muted">
                      {body}
                    </Link>
                  ) : (
                    <button onClick={() => read(n)} className="block w-full text-left">
                      {body}
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </>
  );
}
