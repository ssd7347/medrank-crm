"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMemo, useState } from "react";

import { CollegeForm, RowForm, outcomeMessage, submitChange } from "@/components/master-data-forms";
import { Alert, Badge, Button, Card, DescList, EmptyState, Loading, Modal, PageHeader, Select, Table, Td, cx } from "@/components/ui";
import { errorMessage } from "@/lib/api";
import { DATA_ROLES, useAuth } from "@/lib/auth";
import { formatNumber, formatRupees, label } from "@/lib/format";
import type { ChangeEntity, ChangeRequest, CollegeDetail, CutoffRow, FeeRow, SeatRow } from "@/lib/types";
import { useApi } from "@/lib/use-api";

type Tab = "SEAT_MATRIX" | "CUTOFF" | "FEE";
const TABS: { key: Tab; label: string }[] = [
  { key: "SEAT_MATRIX", label: "Seat matrix" },
  { key: "CUTOFF", label: "Closing ranks" },
  { key: "FEE", label: "Fees" },
];

export default function CollegeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const canPropose = hasRole(...DATA_ROLES);
  const { data, error, loading, reload } = useApi<CollegeDetail>(`/api/colleges/${id}`);
  const [tab, setTab] = useState<Tab>("SEAT_MATRIX");
  const [year, setYear] = useState("");
  const [editingCollege, setEditingCollege] = useState(false);
  const [rowModal, setRowModal] = useState<{ kind: Tab; row?: SeatRow | FeeRow | CutoffRow } | null>(null);
  const [notice, setNotice] = useState<{ tone: "green" | "red"; text: string } | null>(null);

  const years = useMemo(() => {
    if (!data) return [];
    const all = [...data.seatMatrix, ...data.cutoffs, ...data.fees].map((r) => r.academicYear);
    return [...new Set(all)].sort((a, b) => b - a).map(String);
  }, [data]);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "College not found"}</Alert>;
  const c = data.college;

  function done(r: ChangeRequest) {
    setRowModal(null);
    setEditingCollege(false);
    setNotice({ tone: "green", text: outcomeMessage(r) });
    reload();
  }

  async function remove(kind: ChangeEntity, rowId: number) {
    if (!confirm("Request deletion of this row?")) return;
    try {
      done(await submitChange(kind, "DELETE", rowId, {}));
    } catch (e) {
      setNotice({ tone: "red", text: errorMessage(e) });
    }
  }

  const byYear = <T extends { academicYear: number }>(rows: T[]) => (year ? rows.filter((r) => String(r.academicYear) === year) : rows);
  const rowActions = (kind: Tab, row: SeatRow | FeeRow | CutoffRow) =>
    canPropose && (
      <Td className="text-right whitespace-nowrap">
        <Button size="sm" variant="ghost" onClick={() => setRowModal({ kind, row })}>
          Edit
        </Button>
        <Button size="sm" variant="ghost" onClick={() => remove(kind, row.id)}>
          Delete
        </Button>
      </Td>
    );

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/colleges" className="text-ink-soft hover:text-ink">
          ← Colleges
        </Link>
      </div>
      <PageHeader
        title={c.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone="indigo">{label(c.collegeType)}</Badge>
            {c.nmcRecognized ? <Badge tone="green">NMC recognised</Badge> : <Badge tone="red">Not NMC recognised</Badge>}
            <span>
              {c.city ? `${c.city}, ` : ""}
              {c.state}
            </span>
          </span>
        }
        actions={canPropose && <Button variant="secondary" onClick={() => setEditingCollege(true)}>Edit college</Button>}
      />
      {notice && (
        <div className="mb-4">
          <Alert tone={notice.tone}>{notice.text}</Alert>
        </div>
      )}

      <Card className="mb-6">
        <DescList
          items={[
            ["Code", c.code],
            ["Affiliated university", c.affiliatedUniversity],
            ["Established", c.establishedYear],
            [
              "Website",
              c.website ? (
                <a href={c.website} target="_blank" rel="noopener noreferrer" className="text-brand-700 hover:underline">
                  {c.website}
                </a>
              ) : null,
            ],
          ]}
        />
      </Card>

      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div role="tablist" className="inline-flex rounded-lg border border-line bg-surface p-0.5">
          {TABS.map((t) => (
            <button
              key={t.key}
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={cx("rounded-md px-3 py-1.5 text-sm", tab === t.key ? "bg-brand-600 text-on-brand" : "text-ink-soft hover:text-ink")}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <Select aria-label="Academic year" value={year} onChange={(e) => setYear(e.target.value)} options={years} placeholder="All years" className="w-36" />
          {canPropose && (
            <Button size="sm" onClick={() => setRowModal({ kind: tab })}>
              Add row
            </Button>
          )}
        </div>
      </div>

      <Card>
        <div className="-m-4">
          {tab === "SEAT_MATRIX" &&
            (byYear(data.seatMatrix).length === 0 ? (
              <EmptyState title="No seat matrix yet" />
            ) : (
              <Table head={["Year", "Round", "Course", "Quota", "Category", "Seats", ...(canPropose ? [""] : [])]}>
                {byYear(data.seatMatrix).map((r) => (
                  <tr key={r.id}>
                    <Td>{r.academicYear}</Td>
                    <Td>{label(r.counsellingRound)}</Td>
                    <Td>{r.course}</Td>
                    <Td>{label(r.quota)}</Td>
                    <Td>
                      {r.category}
                      {r.pwd && " (PwD)"}
                    </Td>
                    <Td className="font-medium tabular-nums">{r.seats}</Td>
                    {rowActions("SEAT_MATRIX", r)}
                  </tr>
                ))}
              </Table>
            ))}
          {tab === "CUTOFF" &&
            (byYear(data.cutoffs).length === 0 ? (
              <EmptyState title="No closing ranks yet" />
            ) : (
              <Table head={["Year", "Round", "Course", "Quota", "Category", "Closing rank", ...(canPropose ? [""] : [])]}>
                {byYear(data.cutoffs).map((r) => (
                  <tr key={r.id}>
                    <Td>{r.academicYear}</Td>
                    <Td>{label(r.counsellingRound)}</Td>
                    <Td>{r.course}</Td>
                    <Td>{label(r.quota)}</Td>
                    <Td>
                      {r.category}
                      {r.pwd && " (PwD)"}
                    </Td>
                    <Td className="font-medium tabular-nums">{formatNumber(r.closingRank)}</Td>
                    {rowActions("CUTOFF", r)}
                  </tr>
                ))}
              </Table>
            ))}
          {tab === "FEE" &&
            (byYear(data.fees).length === 0 ? (
              <EmptyState title="No fee data yet" />
            ) : (
              <Table head={["Year", "Course", "Quota", "Tuition / year", "Other / year", "Notes", ...(canPropose ? [""] : [])]}>
                {byYear(data.fees).map((r) => (
                  <tr key={r.id}>
                    <Td>{r.academicYear}</Td>
                    <Td>{r.course}</Td>
                    <Td>{label(r.quota)}</Td>
                    <Td className="font-medium tabular-nums">{formatRupees(r.annualTuition)}</Td>
                    <Td className="tabular-nums">{formatRupees(r.otherFees)}</Td>
                    <Td className="text-ink-soft">{r.notes ?? ""}</Td>
                    {rowActions("FEE", r)}
                  </tr>
                ))}
              </Table>
            ))}
        </div>
      </Card>
      <p className="mt-3 text-xs text-ink-faint">College fees are for reference only; confirm with the official fee notification.</p>

      <Modal open={editingCollege} onClose={() => setEditingCollege(false)} title="Edit college" wide>
        {editingCollege && <CollegeForm college={c} onDone={done} />}
      </Modal>
      <Modal open={!!rowModal} onClose={() => setRowModal(null)} title={rowModal?.row ? "Edit row" : "Add row"} wide>
        {rowModal && <RowForm kind={rowModal.kind} collegeId={c.id} row={rowModal.row} onDone={done} />}
      </Modal>
    </>
  );
}
