"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Textarea } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatRupees } from "@/lib/format";
import type { PackageView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

export default function PackagesPage() {
  const { data, error, loading, reload } = useApi<PackageView[]>("/api/fee-packages");
  const [editing, setEditing] = useState<PackageView | "new" | null>(null);

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/fees" className="text-ink-soft hover:text-ink">
          ← Fees & dues
        </Link>
      </div>
      <PageHeader title="Service packages" subtitle="Your consultancy offerings and their instalment schedule." actions={<Button onClick={() => setEditing("new")}>New package</Button>} />
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : !data?.length ? (
        <Card>
          <EmptyState title="No packages yet">Create packages such as &quot;Basic guidance&quot; or &quot;Complete admission support&quot;.</EmptyState>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {data.map((p) => (
            <Card
              key={p.id}
              title={
                <span className="flex items-center gap-2">
                  {p.name} {!p.active && <Badge>Inactive</Badge>}
                </span>
              }
              actions={
                <Button size="sm" variant="ghost" onClick={() => setEditing(p)}>
                  Edit
                </Button>
              }
            >
              <p className="text-2xl font-semibold tabular-nums">{formatRupees(p.totalAmount)}</p>
              {p.description && <p className="mt-1 text-sm text-ink-soft">{p.description}</p>}
              <ul className="mt-3 space-y-1 text-sm">
                {p.installments.map((i) => (
                  <li key={i.label} className="flex justify-between gap-2">
                    <span>
                      {i.label} <span className="text-xs text-ink-faint">day {i.dueOffsetDays}</span>
                    </span>
                    <span className="tabular-nums">{formatRupees(i.amount)}</span>
                  </li>
                ))}
              </ul>
            </Card>
          ))}
        </div>
      )}
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "New package" : "Edit package"} wide>
        {editing !== null && (
          <PackageForm
            pkg={editing === "new" ? undefined : editing}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function PackageForm({ pkg, onDone }: { pkg?: PackageView; onDone: () => void }) {
  const [name, setName] = useState(pkg?.name ?? "");
  const [description, setDescription] = useState(pkg?.description ?? "");
  const [active, setActive] = useState(pkg?.active ?? true);
  const [rows, setRows] = useState(
    pkg?.installments.map((i) => ({ label: i.label, amount: String(i.amount), dueOffsetDays: String(i.dueOffsetDays) })) ?? [
      { label: "Registration", amount: "", dueOffsetDays: "0" },
    ],
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const total = rows.reduce((s, r) => s + (Number(r.amount) || 0), 0);

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        const body = {
          name,
          description: description || null,
          active,
          totalAmount: total,
          installments: rows.map((r) => ({ label: r.label, amount: Number(r.amount), dueOffsetDays: Number(r.dueOffsetDays) })),
        };
        try {
          if (pkg) await api(`/api/fee-packages/${pkg.id}`, { method: "PUT", body });
          else await api("/api/fee-packages", { body });
          onDone();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Field label="Name" required>{(id) => <Input id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="What's included">{(id) => <Textarea id={id} rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-medium">Instalments</legend>
        {rows.map((r, i) => (
          <div key={i} className="grid grid-cols-[1fr_7rem_5rem_auto] gap-2">
            <Input aria-label="Label" required placeholder="Label" value={r.label} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} />
            <Input aria-label="Amount" required type="number" inputMode="decimal" min={0} placeholder="₹" value={r.amount} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} />
            <Input aria-label="Due after days" required type="number" min={0} max={730} title="Days after the plan starts" value={r.dueOffsetDays} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, dueOffsetDays: e.target.value } : x)))} />
            <Button type="button" variant="ghost" size="sm" aria-label="Remove" disabled={rows.length === 1} onClick={() => setRows(rows.filter((_, j) => j !== i))}>
              ✕
            </Button>
          </div>
        ))}
        <div className="flex items-center justify-between">
          <Button type="button" size="sm" variant="secondary" onClick={() => setRows([...rows, { label: "", amount: "", dueOffsetDays: "30" }])}>
            Add instalment
          </Button>
          <span className="text-sm">
            Total <b className="tabular-nums">{formatRupees(total)}</b>
          </span>
        </div>
        <p className="text-xs text-ink-faint">Third column: due this many days after the student&apos;s plan starts.</p>
      </fieldset>
      <Checkbox label="Active (offered for new plans)" checked={active} onChange={(e) => setActive(e.target.checked)} />
      <div>
        <Button type="submit" loading={saving}>
          Save package
        </Button>
      </div>
    </form>
  );
}
