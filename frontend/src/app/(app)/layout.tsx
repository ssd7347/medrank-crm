"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Logo } from "@/components/logo";
import { Loading, cx } from "@/components/ui";
import { DATA_ROLES, LEAD_ROLES, STUDENT_ROLES, useAuth } from "@/lib/auth";
import { label } from "@/lib/format";
import type { Role } from "@/lib/types";

type NavItem = { href: string; label: string; roles?: Role[] };

const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: "Work",
    items: [
      { href: "/dashboard", label: "Dashboard" },
      { href: "/leads", label: "Leads", roles: LEAD_ROLES },
      { href: "/follow-ups", label: "My follow-ups", roles: LEAD_ROLES },
      { href: "/students", label: "Students", roles: STUDENT_ROLES },
    ],
  },
  {
    section: "Data",
    items: [
      { href: "/colleges", label: "Colleges & seats" },
      { href: "/approvals", label: "Data approvals", roles: DATA_ROLES },
    ],
  },
  {
    section: "Admin",
    items: [
      { href: "/admin/users", label: "Staff users", roles: ["SUPER_ADMIN"] },
      { href: "/admin/associates", label: "Referral associates", roles: ["SUPER_ADMIN"] },
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
    <nav className="flex flex-1 flex-col gap-5 px-3 py-4" aria-label="Main">
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
    <div className="border-t border-line p-3">
      <Link href="/account" className="block rounded-lg px-2 py-1.5 hover:bg-muted">
        <p className="truncate text-sm font-medium text-ink">{user.fullName}</p>
        <p className="truncate text-xs text-ink-faint">{label(user.role)}</p>
      </Link>
      <button onClick={logout} className="mt-1 w-full rounded-lg px-2 py-1.5 text-left text-sm text-ink-soft hover:bg-muted hover:text-ink">
        Log out
      </button>
    </div>
  );

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[15rem_1fr]">
      <aside className="sticky top-0 hidden h-screen flex-col border-r border-line bg-surface lg:flex">
        <div className="px-5 py-4">
          <Logo />
        </div>
        {nav}
        {account}
      </aside>

      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden">
        <Logo />
        <button
          onClick={() => setMenuOpen((o) => !o)}
          className="rounded-lg border border-line px-3 py-1.5 text-sm"
          aria-expanded={menuOpen}
          aria-controls="mobile-nav"
        >
          {menuOpen ? "Close" : "Menu"}
        </button>
      </header>
      {menuOpen && (
        <div id="mobile-nav" className="fixed inset-x-0 top-[57px] bottom-0 z-10 flex flex-col overflow-y-auto bg-surface lg:hidden">
          {nav}
          {account}
        </div>
      )}

      <main className="min-w-0 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
    </div>
  );
}
