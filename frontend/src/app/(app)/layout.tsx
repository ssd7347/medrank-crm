"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Logo } from "@/components/logo";
import { NotificationBell } from "@/components/notification-bell";
import { Loading, cx } from "@/components/ui";
import { DATA_ROLES, DOCUMENT_ROLES, FEE_READ_ROLES, FEE_WRITE_ROLES, LEAD_ROLES, STUDENT_ROLES, useAuth } from "@/lib/auth";
import { label } from "@/lib/format";
import type { Role } from "@/lib/types";

type NavItem = { href: string; label: string; roles?: Role[] };

const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: "Work",
    items: [
      { href: "/dashboard", label: "Dashboard" },
      { href: "/priorities", label: "What to do next", roles: LEAD_ROLES },
      { href: "/leads", label: "Leads", roles: LEAD_ROLES },
      { href: "/follow-ups", label: "My follow-ups", roles: LEAD_ROLES },
      { href: "/students", label: "Students", roles: STUDENT_ROLES },
      { href: "/tickets", label: "Helpdesk" },
      { href: "/voice", label: "AI voice agent" },
    ],
  },
  {
    section: "Counselling",
    items: [
      { href: "/counselling/desk", label: "Round desk", roles: ["SUPER_ADMIN", "COUNSELLOR"] },
      { href: "/counselling/calendar", label: "Calendar" },
      { href: "/predictor", label: "College predictor" },
    ],
  },
  {
    section: "Operations",
    items: [
      { href: "/documents", label: "Documents desk", roles: DOCUMENT_ROLES },
      { href: "/fees", label: "Fees & dues", roles: FEE_READ_ROLES.filter((r) => r !== "COUNSELLOR") },
      { href: "/commissions", label: "Commissions", roles: FEE_WRITE_ROLES },
      { href: "/loans", label: "Loan desk", roles: ["SUPER_ADMIN", "LOAN_DESK", "COUNSELLOR"] },
      { href: "/grievances", label: "Grievances" },
    ],
  },
  {
    section: "Growth",
    items: [
      { href: "/analytics", label: "Analytics", roles: ["SUPER_ADMIN"] },
      { href: "/reports", label: "Report builder", roles: ["SUPER_ADMIN"] },
      { href: "/marketing", label: "Marketing", roles: ["SUPER_ADMIN"] },
      { href: "/alumni", label: "Alumni & referrals", roles: ["SUPER_ADMIN", "COUNSELLOR"] },
    ],
  },
  {
    section: "Data",
    items: [
      { href: "/colleges", label: "Colleges & seats" },
      { href: "/refund-rules", label: "Refund rules" },
      { href: "/approvals", label: "Data approvals", roles: DATA_ROLES },
    ],
  },
  {
    section: "Admin",
    items: [
      { href: "/admin/users", label: "Staff users", roles: ["SUPER_ADMIN"] },
      { href: "/admin/branches", label: "Branches", roles: ["SUPER_ADMIN"] },
      { href: "/admin/associates", label: "Associates & sub-agents", roles: ["SUPER_ADMIN", "ACCOUNTANT"] },
      { href: "/staff", label: "Team performance", roles: ["SUPER_ADMIN"] },
      { href: "/admin/document-types", label: "Document checklist", roles: ["SUPER_ADMIN"] },
      { href: "/admin/agreements", label: "Agreement wording", roles: ["SUPER_ADMIN"] },
      { href: "/admin/faq", label: "Assistant knowledge base", roles: ["SUPER_ADMIN"] },
      { href: "/admin/integrations", label: "Connected services", roles: ["SUPER_ADMIN"] },
    ],
  },
];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, logout, hasRole } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user, router]);

  // Close the mobile menu after navigating.
  const [lastPath, setLastPath] = useState(pathname);
  if (lastPath !== pathname) {
    setLastPath(pathname);
    setMenuOpen(false);
  }

  if (loading || !user) {
    return (
      <div className="min-h-screen">
        <Loading />
      </div>
    );
  }

  const sections = NAV.map((s) => ({ ...s, items: s.items.filter((i) => !i.roles || hasRole(...i.roles)) })).filter(
    (s) => s.items.length > 0,
  );

  const nav = (
    // The menu scrolls on its own; reaching its end must not start scrolling the page behind it.
    <nav className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto overscroll-contain px-3 py-4" aria-label="Main">
      {sections.map((s) => (
        <div key={s.section}>
          <p className="mb-1 px-2 text-[11px] font-semibold tracking-wider text-ink-faint uppercase">{s.section}</p>
          <ul className="space-y-0.5">
            {s.items.map((item) => {
              const active = pathname === item.href || pathname.startsWith(item.href + "/");
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className={cx(
                      "block rounded-lg px-2.5 py-1.5 text-sm",
                      active ? "bg-brand-50 font-medium text-brand-800" : "text-ink-soft hover:bg-muted hover:text-ink",
                    )}
                  >
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );

  const account = (
    <div className="shrink-0 border-t border-line p-3">
      <Link href="/account" className="block rounded-lg px-2 py-1.5 hover:bg-muted">
        <p className="truncate text-sm font-medium text-ink">{user.fullName}</p>
        <p className="truncate text-xs text-ink-faint">
          {label(user.role)}
          {user.branch && ` · ${user.branch.name}`}
        </p>
      </Link>
      <button onClick={logout} className="mt-1 w-full rounded-lg px-2 py-1.5 text-left text-sm text-ink-soft hover:bg-muted hover:text-ink">
        Log out
      </button>
    </div>
  );

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[15rem_1fr] print:block">
      <aside className="sticky top-0 hidden h-screen flex-col border-r border-line bg-surface lg:flex print:hidden">
        <div className="flex items-center justify-between px-5 py-4">
          <Logo />
          <NotificationBell />
        </div>
        {nav}
        {account}
      </aside>

      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden print:hidden">
        <Logo />
        <div className="flex items-center gap-2">
          <NotificationBell />
          <button
          onClick={() => setMenuOpen((o) => !o)}
          className="rounded-lg border border-line px-3 py-1.5 text-sm"
          aria-expanded={menuOpen}
          aria-controls="mobile-nav"
        >
          {menuOpen ? "Close" : "Menu"}
          </button>
        </div>
      </header>
      {menuOpen && (
        <div id="mobile-nav" className="fixed inset-x-0 top-[57px] bottom-0 z-10 flex flex-col overscroll-contain bg-surface lg:hidden">
          {nav}
          {account}
        </div>
      )}

      <main className="min-w-0 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
    </div>
  );
}
