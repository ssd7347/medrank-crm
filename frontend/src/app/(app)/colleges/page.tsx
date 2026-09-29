"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { CollegeForm, outcomeMessage } from "@/components/master-data-forms";
import { Alert, Badge, Button, Card, EmptyState, Input, Loading, Modal, PageHeader, Pagination, Select, Table, Td } from "@/components/ui";
import { DATA_ROLES, useAuth } from "@/lib/auth";
import { INDIAN_STATES, label } from "@/lib/format";
import { COLLEGE_TYPES, type College, type Page } from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function CollegesPage() {
  const { hasRole } = useAuth();
  const canPropose = hasRole(...DATA_ROLES);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [state, setState] = useState("");
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setQ(search.trim());
      setPage(0);
    }, 350);
    return () => clearTimeout(t);
  }, [search]);

  const { data, error, loading, reload } = useApi<Page<College>>("/api/colleges", { q, state, type, page, size: 50 });

  return (
    <>
      <PageHeader
        title="Colleges & seats"
        subtitle="Approved master data. Changes go through admin approval before they appear here."
        actions={canPropose && <Button onClick={() => setAdding(true)}>Add college</Button>}
      />
      {notice && (
        <div className="mb-4">
          <Alert tone="green">{notice}</Alert>
        </div>
      )}
      <Card className="overflow-hidden">
        <div className="-m-4">
          <div className="grid gap-2 border-b border-line p-3 sm:grid-cols-3">
            <Input placeholder="Search name, code or city" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search colleges" />
            <Select aria-label="State" value={state} onChange={(e) => { setState(e.target.value); setPage(0); }} options={INDIAN_STATES} placeholder="All states" />
            <Select aria-label="Type" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} options={COLLEGE_TYPES} labelFor={label} placeholder="All types" />
          </div>
          {error && (
            <div className="p-3">
              <Alert>{error}</Alert>
            </div>
          )}
          {loading && !data ? (
            <Loading />
          ) : !data?.items.length ? (
            <EmptyState title="No colleges yet">{canPropose ? "Add colleges one by one, then upload seat matrix and cutoff CSVs from Data approvals." : "The data team has not added any colleges yet."}</EmptyState>
          ) : (
            <>
              <Table head={["College", "Code", "Type", "State", "City", "NMC"]}>
                {data.items.map((c) => (
                  <tr key={c.id} className="hover:bg-muted/60">
                    <Td>
                      <Link href={`/colleges/${c.id}`} className="font-medium text-brand-800 hover:underline">
                        {c.name}
                      </Link>
                    </Td>
                    <Td className="font-mono text-xs">{c.code ?? "—"}</Td>
                    <Td>{label(c.collegeType)}</Td>
                    <Td>{c.state}</Td>
                    <Td>{c.city ?? "—"}</Td>
                    <Td>{c.nmcRecognized ? <Badge tone="green">Recognised</Badge> : <Badge tone="red">Not recognised</Badge>}</Td>
                  </tr>
                ))}
              </Table>
              <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
            </>
          )}
        </div>
      </Card>

      <Modal open={adding} onClose={() => setAdding(false)} title="Add college" wide>
        {adding && (
          <CollegeForm
            onDone={(r) => {
              setAdding(false);
              setNotice(outcomeMessage(r));
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}
