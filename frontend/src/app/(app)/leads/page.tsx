"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { LeadStatusBadge } from "@/components/badges";
import { Alert, ButtonLink, Card, EmptyState, Input, Loading, PageHeader, Pagination, Select, Table, Td, cx } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { formatDate, formatNumber, label } from "@/lib/format";
import { LEAD_SOURCES, LEAD_STATUSES, type LeadListItem, type Page, type UserRef } from "@/lib/types";
import { useApi } from "@/lib/use-api";

function LeadsView() {
  const router = useRouter();
  const params = useSearchParams();
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");

  const status = params.get("status") ?? "";
  const source = params.get("source") ?? "";
  const owner = params.get("owner") ?? "";
  const page = Number(params.get("page") ?? 0);
  const q = params.get("q") ?? "";
  const [search, setSearch] = useState(q);

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    router.replace(`/leads?${next.toString()}`);
  }

  // Debounce the search box into the URL.
  useEffect(() => {
    const t = setTimeout(() => {
      if (search !== q) setParam("q", search.trim());
    }, 350);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const staff = useApi<UserRef[]>(isAdmin ? "/api/users/assignable" : null);
  const { data, error, loading } = useApi<Page<LeadListItem>>("/api/leads", {
    q,
    status,
    source,
    page,
    size: 25,
    counsellorId: owner && owner !== "unassigned" ? owner : undefined,
    unassigned: owner === "unassigned" ? true : undefined,
  });

  const ownerOptions = [
    { value: "unassigned", label: "Unassigned" },
    ...(staff.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName })),
  ];

  return (
    <>
      <PageHeader
        title="Leads"
        subtitle={isAdmin ? "All inquiries." : "Leads assigned to you, plus unassigned leads you can pick up."}
        actions={
          <>
            <ButtonLink href="/leads/import" variant="secondary">
              Import CSV
            </ButtonLink>
            <ButtonLink href="/leads/new">New lead</ButtonLink>
          </>
        }
      />

      <div className="mb-3 flex flex-wrap gap-1.5">
        <button
          onClick={() => setParam("status", "")}
          className={cx("rounded-full border px-3 py-1 text-xs", !status ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}
        >
          All stages
        </button>
        {LEAD_STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => setParam("status", s)}
            className={cx("rounded-full border px-3 py-1 text-xs", status === s ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}
          >
            {label(s)}
          </button>
        ))}
      </div>

      <Card className="overflow-hidden">
        <div className="-m-4">
          <div className="grid gap-2 border-b border-line p-3 sm:grid-cols-3">
            <Input placeholder="Search name, phone or NEET roll no." value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search leads" />
            <Select aria-label="Source" value={source} onChange={(e) => setParam("source", e.target.value)} options={LEAD_SOURCES} labelFor={label} placeholder="Any source" />
            {isAdmin && (
              <Select aria-label="Assigned to" value={owner} onChange={(e) => setParam("owner", e.target.value)} options={ownerOptions} placeholder="Anyone" />
            )}
          </div>
          {error && (
            <div className="p-3">
              <Alert>{error}</Alert>
            </div>
          )}
          {loading && !data ? (
            <Loading />
          ) : !data?.items.length ? (
            <EmptyState title="No leads found">{q || status || source || owner ? "Try clearing the filters." : "Add your first lead to get started."}</EmptyState>
          ) : (
            <>
              <Table head={["Name", "Phone", "NEET score / AIR", "Category", "Source", "Stage", "Assigned to", "Added"]}>
                {data.items.map((l) => (
                  <tr key={l.id} className="hover:bg-muted/60">
                    <Td>
                      <Link href={`/leads/${l.id}`} className="font-medium text-brand-800 hover:underline">
                        {l.fullName}
                      </Link>
                      {l.studentId && <span className="ml-2 text-xs text-ink-faint">student</span>}
                    </Td>
                    <Td className="tabular-nums">{l.phone}</Td>
                    <Td className="tabular-nums">
                      {formatNumber(l.neetScore)} / {formatNumber(l.neetAir)}
                    </Td>
                    <Td>{l.category ?? "—"}</Td>
                    <Td className="whitespace-nowrap">{label(l.source)}</Td>
                    <Td>
                      <LeadStatusBadge status={l.status} />
                    </Td>
                    <Td className="whitespace-nowrap">{l.assignedCounsellor?.fullName ?? <span className="text-amber-700">Unassigned</span>}</Td>
                    <Td className="whitespace-nowrap text-ink-soft">{formatDate(l.createdAt)}</Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={(p) => setParam("page", String(p))} />
            </>
          )}
        </div>
      </Card>
    </>
  );
}

export default function LeadsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <LeadsView />
    </Suspense>
  );
}
