"use client";

import {
  BarChart3,
  BookOpen,
  Building2,
  CalendarClock,
  CalendarDays,
  CheckCheck,
  ClipboardList,
  Compass,
  FileCheck2,
  FileSignature,
  FileText,
  GraduationCap,
  Handshake,
  HandCoins,
  Landmark,
  LayoutDashboard,
  LifeBuoy,
  ListChecks,
  LogOut,
  Megaphone,
  Menu,
  MessageSquareWarning,
  PhoneCall,
  Plug,
  ReceiptIndianRupee,
  School,
  Target,
  TrendingUp,
  UserCog,
  UserPlus,
  Users,
  Wallet,
  X,
  type LucideIcon,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Logo } from "@/components/logo";
import { NotificationBell } from "@/components/notification-bell";
import { Loading, cx } from "@/components/ui";
import { DATA_ROLES, DOCUMENT_ROLES, FEE_READ_ROLES, FEE_WRITE_ROLES, LEAD_ROLES, STUDENT_ROLES, useAuth } from "@/lib/auth";
import { label } from "@/lib/format";
import type { Role } from "@/lib/types";

type NavItem = { href: string; label: string; icon: LucideIcon; roles?: Role[] };

const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: "Work",
    items: [
      { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
      { href: "/priorities", label: "What to do next", icon: Target, roles: LEAD_ROLES },
      { href: "/leads", label: "Leads", icon: UserPlus, roles: LEAD_ROLES },
      { href: "/follow-ups", label: "My follow-ups", icon: CalendarClock, roles: LEAD_ROLES },
      { href: "/students", label: "Students", icon: GraduationCap, roles: STUDENT_ROLES },
      { href: "/tickets", label: "Helpdesk", icon: LifeBuoy },
      { href: "/voice", label: "AI voice agent", icon: PhoneCall },
    ],
  },
  {
    section: "Counselling",
    items: [
      { href: "/counselling/desk", label: "Round desk", icon: ClipboardList, roles: ["SUPER_ADMIN", "COUNSELLOR"] },
      { href: "/counselling/calendar", label: "Calendar", icon: CalendarDays },
      { href: "/predictor", label: "College predictor", icon: Compass },
    ],
  },
  {
    section: "Operations",
    items: [
      { href: "/documents", label: "Documents desk", icon: FileText, roles: DOCUMENT_ROLES },
      { href: "/fees", label: "Fees & dues", icon: Wallet, roles: FEE_READ_ROLES.filter((r) => r !== "COUNSELLOR") },
      { href: "/commissions", label: "Commissions", icon: HandCoins, roles: FEE_WRITE_ROLES },
      { href: "/loans", label: "Loan desk", icon: Landmark, roles: ["SUPER_ADMIN", "LOAN_DESK", "COUNSELLOR"] },
      { href: "/grievances", label: "Grievances", icon: MessageSquareWarning },
    ],
  },
  {
    section: "Growth",
    items: [
      { href: "/analytics", label: "Analytics", icon: BarChart3, roles: ["SUPER_ADMIN"] },
      { href: "/reports", label: "Report builder", icon: ReceiptIndianRupee, roles: ["SUPER_ADMIN"] },
      { href: "/marketing", label: "Marketing", icon: Megaphone, roles: ["SUPER_ADMIN"] },
      { href: "/alumni", label: "Alumni & referrals", icon: Handshake, roles: ["SUPER_ADMIN", "COUNSELLOR"] },
    ],
  },
  {
    section: "Data",
    items: [
      { href: "/colleges", label: "Colleges & seats", icon: School },
      { href: "/refund-rules", label: "Refund rules", icon: ListChecks },
      { href: "/approvals", label: "Data approvals", icon: CheckCheck, roles: DATA_ROLES },
    ],
  },
  {
    section: "Administration",
    items: [
      { href: "/admin/users", label: "Staff users", icon: UserCog, roles: ["SUPER_ADMIN"] },
      { href: "/admin/branches", label: "Branches", icon: Building2, roles: ["SUPER_ADMIN"] },
      { href: "/admin/associates", label: "Associates & sub-agents", icon: Users, roles: ["SUPER_ADMIN", "ACCOUNTANT"] },
      { href: "/staff", label: "Team performance", icon: TrendingUp, roles: ["SUPER_ADMIN"] },
      { href: "/admin/document-types", label: "Document checklist", icon: FileCheck2, roles: ["SUPER_ADMIN"] },
      { href: "/admin/agreements", label: "Agreement wording", icon: FileSignature, roles: ["SUPER_ADMIN"] },
      { href: "/admin/faq", label: "Assistant knowledge base", icon: BookOpen, roles: ["SUPER_ADMIN"] },
      { href: "/admin/integrations", label: "Connected services", icon: Plug, roles: ["SUPER_ADMIN"] },
    ],
  },
];

function initials(name: string) {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase() || "·";
}

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
      <div className="grid min-h-screen place-items-center">
        <Loading />
      </div>
    );
  }

  const sections = NAV.map((s) => ({ ...s, items: s.items.filter((i) => !i.roles || hasRole(...i.roles)) })).filter((s) => s.items.length > 0);

  const nav = (
    // The menu scrolls on its own; reaching its end must not start scrolling the page behind it.
    <nav className="flex min-h-0 flex-1 flex-col gap-7 overflow-y-auto overscroll-contain px-4 py-6" aria-label="Main">
      {sections.map((s) => (
        <div key={s.section}>
          <p className="eyebrow mb-2.5 flex items-center gap-2 px-3 text-ink-faint">
            {s.section}
            <span className="h-px flex-1 bg-gradient-to-r from-brand-300 to-transparent" />
          </p>
          <ul className="space-y-0.5">
            {s.items.map((item) => {
              const active = pathname === item.href || pathname.startsWith(item.href + "/");
              const Icon = item.icon;
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className={cx(
                      "group relative flex items-center gap-3 rounded-md px-3 py-2 text-[0.84rem] transition-colors duration-200",
                      active ? "bg-gradient-to-r from-brand-100 to-brand-50/0 font-semibold text-ink" : "text-ink-soft hover:bg-brand-50 hover:text-ink",
                    )}
                  >
                    {/* The active page carries a fine gold bar at its left edge. */}
                    <span
                      aria-hidden
                      className={cx("absolute top-1.5 bottom-1.5 left-0 w-[2px] rounded-full bg-brand-600 transition-opacity", active ? "opacity-100" : "opacity-0")}
                    />
                    <Icon
                      aria-hidden
                      strokeWidth={1.6}
                      className={cx("h-[18px] w-[18px] shrink-0 transition-colors", active ? "text-brand-700" : "text-ink-faint group-hover:text-brand-700")}
                    />
                    <span className="truncate">{item.label}</span>
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
    <div className="shrink-0 border-t border-line p-4">
      <div className="flex items-center gap-3">
        <Link href="/account" className="flex min-w-0 flex-1 items-center gap-3 rounded-md p-1.5 transition hover:bg-brand-50">
          <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full border border-brand-400 bg-gradient-to-br from-brand-50 to-brand-200 font-display text-sm font-semibold text-brand-900">
            {initials(user.fullName)}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-semibold text-ink">{user.fullName}</span>
            <span className="block truncate text-xs text-ink-faint">
              {label(user.role)}
              {user.branch && ` · ${user.branch.name}`}
            </span>
          </span>
        </Link>
        <button
          onClick={logout}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-md text-ink-faint transition hover:bg-brand-50 hover:text-brand-800"
          aria-label="Log out"
          title="Log out"
        >
          <LogOut className="h-[18px] w-[18px]" strokeWidth={1.6} />
        </button>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[16.5rem_1fr] print:block">
      <aside className="sticky top-0 hidden h-screen flex-col border-r border-line bg-gradient-to-b from-surface to-raised lg:flex print:hidden">
        <div className="flex items-center justify-between gap-2 px-5 pt-6 pb-5">
          <Logo />
          <NotificationBell />
        </div>
        <div className="mx-5 h-px bg-gradient-to-r from-brand-600/70 via-brand-600/20 to-transparent" />
        {nav}
        {account}
      </aside>

      <header className="sticky top-0 z-20 flex h-[57px] items-center justify-between border-b border-line bg-surface/95 px-4 backdrop-blur lg:hidden print:hidden">
        <Logo />
        <div className="flex items-center gap-2">
          <NotificationBell />
          <button
            onClick={() => setMenuOpen((o) => !o)}
            className="grid h-9 w-9 place-items-center rounded-md border border-line-strong text-ink transition hover:border-brand-600"
            aria-expanded={menuOpen}
            aria-controls="mobile-nav"
            aria-label={menuOpen ? "Close menu" : "Open menu"}
          >
            {menuOpen ? <X className="h-5 w-5" strokeWidth={1.6} /> : <Menu className="h-5 w-5" strokeWidth={1.6} />}
          </button>
        </div>
      </header>
      {menuOpen && (
        <div id="mobile-nav" className="fixed inset-x-0 top-[57px] bottom-0 z-10 flex flex-col overscroll-contain bg-surface lg:hidden">
          {nav}
          {account}
        </div>
      )}

      <main className="min-w-0 px-4 py-7 sm:px-8 lg:px-12 lg:py-10">
        <div className="mx-auto max-w-[88rem]">{children}</div>
      </main>
    </div>
  );
}
