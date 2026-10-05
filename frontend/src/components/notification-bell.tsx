"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { api } from "@/lib/api";

import { cx } from "./ui";

/** Bell with unread count. Polls every minute and refreshes when a page marks notifications read. */
export function NotificationBell({ className }: { className?: string }) {
  const [unread, setUnread] = useState(0);
  const pathname = usePathname();

  useEffect(() => {
    let alive = true;
    const load = () =>
      api<{ unread: number }>("/api/notifications/unread-count")
        .then((r) => alive && setUnread(r.unread))
        .catch(() => {});
    load();
    const t = setInterval(load, 60_000);
    window.addEventListener("notifications-changed", load);
    return () => {
      alive = false;
      clearInterval(t);
      window.removeEventListener("notifications-changed", load);
    };
  }, [pathname]);

  return (
    <Link
      href="/notifications"
      aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"}
      className={cx("relative inline-grid h-9 w-9 place-items-center rounded-md border border-line-strong bg-surface text-ink-soft transition hover:border-brand-600 hover:text-brand-800", className)}
    >
      <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden>
        <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" strokeLinecap="round" />
      </svg>
      {unread > 0 && (
        <span className="absolute -top-1.5 -right-1.5 min-w-5 rounded-full bg-brand-600 px-1 text-center text-[10px] leading-5 font-bold text-on-brand ring-2 ring-surface">
          {unread > 99 ? "99+" : unread}
        </span>
      )}
    </Link>
  );
}
