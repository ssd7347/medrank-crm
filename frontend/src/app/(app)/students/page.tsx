"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { Alert, ButtonLink, Card, EmptyState, Input, Loading, PageHeader, Pagination, Select, Table, Td } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { INDIAN_STATES, formatDate, formatNumber } from "@/lib/format";
import { CATEGORIES, type Page, type StudentListItem } from "@/lib/types";
import type { Branch } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

export default function StudentsPage() {
  const { hasRole } = useAuth();
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [homeState, setHomeState] = useState("");
  const [page, setPage] = useState(0);
  const [branch, setBranch] = useState("");
  const isAdmin = hasRole("SUPER_ADMIN");
  const branches = useApi<Branch[]>(isAdmin ? "/api/branches" : null);

  useEffect(() => {
    const t = setTimeout(() => {
      setQ(search.trim());
      setPage(0);
    }, 350);
    return () => clearTimeout(t);
  }, [search]);

  const { data, error, loading } = useApi<Page<StudentListItem>>("/api/students", { q, category, homeState, branchId: branch || undefined, page, size: 25 });

  return (
    <>
      <PageHeader
        title="Students"
        subtitle={hasRole("COUNSELLOR") ? "Students assigned to you." : "Students being actively counselled."}
        actions={hasRole("SUPER_ADMIN", "COUNSELLOR") && <ButtonLink href="/students/new">New student</ButtonLink>}
      />
      <Card className="overflow-hidden">
        <div className="-m-4">
          <div className="grid gap-2 border-b border-line p-3 sm:grid-cols-2 lg:grid-cols-4">
            <Input placeholder="Search name, phone or roll no." value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search students" />
            <Select aria-label="Category" value={category} onChange={(e) => { setCategory(e.target.value); setPage(0); }} options={CATEGORIES} placeholder="Any category" />
            <Select aria-label="Home state" value={homeState} onChange={(e) => { setHomeState(e.target.value); setPage(0); }} options={INDIAN_STATES} placeholder="Any home state" />
            {isAdmin && !!branches.data?.length && (
              <Select
                aria-label="Branch"
                value={branch}
                onChange={(e) => {
                  setBranch(e.target.value);
                  setPage(0);
                }}
                options={branches.data.map((b) => ({ value: String(b.id), label: b.name }))}
                placeholder="All branches"
              />
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
            <EmptyState title="No students found">Students are created by converting a qualified lead.</EmptyState>
          ) : (
            <>
              <Table head={["Name", "Phone", "Category", "Home state", "Score", "AIR", "Counsellor", "Since"]}>
                {data.items.map((s) => (
                  <tr key={s.id} className="hover:bg-muted/60">
                    <Td>
                      <Link href={`/students/${s.id}`} className="font-medium text-brand-800 hover:underline">
                        {s.fullName}
                      </Link>
                    </Td>
                    <Td className="tabular-nums">{s.phone}</Td>
                    <Td>{s.category}</Td>
                    <Td>{s.homeState}</Td>
                    <Td className="tabular-nums">{formatNumber(s.neetScore)}</Td>
                    <Td className="tabular-nums">{formatNumber(s.neetAir)}</Td>
                    <Td>{s.assignedCounsellor?.fullName ?? "—"}</Td>
                    <Td className="whitespace-nowrap text-ink-soft">{formatDate(s.createdAt)}</Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
            </>
          )}
        </div>
      </Card>
    </>
  );
}
