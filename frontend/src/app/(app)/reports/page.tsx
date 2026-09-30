"use client";

import { useState } from "react";

import { Alert, Button, Card, Checkbox, EmptyState, Field, Input, Loading, PageHeader, Select } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { formatDate, label } from "@/lib/format";
import { REPORT_DATASETS, type Branch, type ReportDataset, type ReportResult } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

const HELP: Record<ReportDataset, string> = {
  LEADS: "Every inquiry added in the period, with source, campaign and stage.",
  STUDENTS: "Students added in the period, with NEET details and counsellor.",
  PAYMENTS: "Consultancy fee payments received in the period, with receipt numbers.",
  ADMISSIONS: "Students added to the alumni directory (confirmed admissions) in the period.",
  ALLOTMENTS: "Round results recorded in the period and the decision taken on each.",
  TICKETS: "Helpdesk tickets opened in the period.",
};

const PREVIEW_ROWS = 50;

/** Cells that a spreadsheet would run as a formula are neutralised with a leading apostrophe. */
function csvCell(value: string | number | null): string {
  if (value === null || value === undefined) return "";
  let s = String(value);
  if (typeof value === "string" && /^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

export default function ReportsPage() {
  const [dataset, setDataset] = useState<ReportDataset>("LEADS");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [branchId, setBranchId] = useState("");
  const branches = useApi<Branch[]>("/api/branches");
  const [result, setResult] = useState<ReportResult | null>(null);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<ReportResult>(`/api/analytics/reports/${dataset}`, { query: { from, to, branchId } });
      setResult(r);
      setHidden(new Set());
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const shown = result ? result.columns.map((c, i) => ({ ...c, i })).filter((c) => !hidden.has(c.key)) : [];

  function download() {
    if (!result) return;
    const lines = [shown.map((c) => csvCell(c.label)).join(",")];
    const ds = result.dataset;
    for (const row of result.rows) {
      lines.push(shown.map((c) => csvCell(row[c.i] === null || row[c.i] === "" ? null : prettify(row[c.i], c.key, ds))).join(","));
    }
    // The BOM makes Excel read the file as UTF-8, so names in Tamil or Hindi stay readable.
    const blob = new Blob(["﻿" + lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${result.dataset.toLowerCase()}_${result.from}_to_${result.to}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <>
      <div className="print:hidden">
        <PageHeader title="Report builder" subtitle="Pick what you need, choose the columns, then save it for Excel or print it as a PDF." />
        <Card>
          <form onSubmit={run} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5 lg:items-end">
            <Field label="Report" className="lg:col-span-2" hint={HELP[dataset]}>
              {(id) => (
                <Select
                  id={id}
                  value={dataset}
                  onChange={(e) => {
                    setDataset(e.target.value as ReportDataset);
                    setResult(null);
                  }}
                  options={REPORT_DATASETS}
                  labelFor={label}
                />
              )}
            </Field>
            <Field label="From" hint="Empty = last 12 months">
              {(id) => <Input id={id} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />}
            </Field>
            <Field label="To" hint=" ">
              {(id) => <Input id={id} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />}
            </Field>
            {branches.data?.length ? (
              <Field label="Branch" hint=" ">
                {(id) => (
                  <Select
                    id={id}
                    value={branchId}
                    onChange={(e) => setBranchId(e.target.value)}
                    placeholder="All branches"
                    options={branches.data!.map((b) => ({ value: String(b.id), label: b.name }))}
                  />
                )}
              </Field>
            ) : null}
            <div className="sm:col-span-2 lg:col-span-5">
              <Button type="submit" loading={busy}>
                Run report
              </Button>
            </div>
          </form>
        </Card>
        {error && (
          <div className="mt-4">
            <Alert>{error}</Alert>
          </div>
        )}
      </div>

      {busy && !result ? (
        <Loading />
      ) : result ? (
        <div className="mt-6 space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold">{label(result.dataset)}</h2>
              <p className="text-sm text-ink-soft">
                {formatDate(result.from)} to {formatDate(result.to)} · {result.rows.length} {result.rows.length === 1 ? "row" : "rows"}
              </p>
            </div>
            <div className="flex gap-2 print:hidden">
              <Button variant="secondary" onClick={() => window.print()} disabled={!result.rows.length}>
                Print / save as PDF
              </Button>
              <Button onClick={download} disabled={!result.rows.length || !shown.length}>
                Download for Excel (CSV)
              </Button>
            </div>
          </div>
          {result.truncated && <Alert tone="amber">Only the first {result.rows.length} rows are included. Shorten the period to get everything.</Alert>}
          <div className="print:hidden">
            <Alert tone="blue">This file contains personal details such as phone numbers. Share it only with people who need it. Every report run is logged.</Alert>
          </div>

          <fieldset className="print:hidden">
            <legend className="mb-2 text-xs font-medium text-ink-soft">Columns to include</legend>
            <div className="flex flex-wrap gap-x-5 gap-y-2">
              {result.columns.map((c) => (
                <Checkbox
                  key={c.key}
                  label={c.label}
                  checked={!hidden.has(c.key)}
                  onChange={(e) => {
                    const next = new Set(hidden);
                    if (e.target.checked) next.delete(c.key);
                    else next.add(c.key);
                    setHidden(next);
                  }}
                />
              ))}
            </div>
          </fieldset>

          <Card className="overflow-hidden print:border-0 print:shadow-none">
            <div className="-m-4">
              {!result.rows.length ? (
                <EmptyState title="Nothing in this period" />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm print:text-xs">
                    <thead>
                      <tr className="border-b border-line text-left text-xs font-medium text-ink-faint">
                        {shown.map((c) => (
                          <th key={c.key} className="px-3 py-2 whitespace-nowrap">
                            {c.label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-line">
                      {result.rows.map((row, r) => (
                        // On screen only a preview is shown; printing includes every row.
                        <tr key={r} className={r >= PREVIEW_ROWS ? "hidden print:table-row" : undefined}>
                          {shown.map((c) => (
                            <td key={c.key} className="px-3 py-1.5 whitespace-nowrap tabular-nums print:whitespace-normal">
                              {prettify(row[c.i], c.key, result.dataset)}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </Card>
          {result.rows.length > PREVIEW_ROWS && (
            <p className="text-sm text-ink-soft print:hidden">
              Showing the first {PREVIEW_ROWS} rows here. The download and the printout include all {result.rows.length}.
            </p>
          )}
        </div>
      ) : null}
    </>
  );
}

/** Enum codes like ADMISSION_CONFIRMED read better as labels; everything else is shown as-is. */
const CODE_COLUMNS = new Set(["source", "status", "priority", "method", "decision", "via", "quota"]);

function prettify(value: string | number | null, column: string, dataset: ReportDataset): string | number {
  if (value === null || value === "") return "—";
  if (typeof value !== "string") return value;
  return CODE_COLUMNS.has(column) || (column === "category" && dataset === "TICKETS") ? label(value) : value;
}
