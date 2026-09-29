"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Button, Card, PageHeader, Table, Td } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { LEAD_SOURCES, type ImportResult } from "@/lib/types";

const TEMPLATE = "full_name,phone,email,neet_roll_no,neet_score,neet_air,category,home_state,source,notes\n" +
  "Priya S,9876543210,priya@example.com,,612,14500,OBC,Tamil Nadu,SEMINAR,Met at Madurai camp\n";

export default function ImportLeadsPage() {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function upload(e: React.FormEvent) {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const form = new FormData();
      form.append("file", file);
      setResult(await api<ImportResult>("/api/leads/import", { form, method: "POST" }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const templateHref = `data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE)}`;

  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/leads" className="text-ink-soft hover:text-ink">
          ← Leads
        </Link>
      </div>
      <PageHeader title="Import leads from CSV" subtitle="Existing phone numbers and roll numbers are skipped, never overwritten." />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Upload" className="lg:col-span-2">
          <form onSubmit={upload} className="space-y-4">
            {error && <Alert>{error}</Alert>}
            <input
              type="file"
              accept=".csv,text/csv"
              aria-label="CSV file"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-brand-50 file:px-3 file:py-2 file:text-sm file:font-medium file:text-brand-800 hover:file:bg-brand-100"
            />
            <Button type="submit" loading={busy} disabled={!file}>
              Import
            </Button>
          </form>
          {result && (
            <div className="mt-6 space-y-3">
              <Alert tone={result.errors.length ? "amber" : "green"}>
                Imported <b>{result.imported}</b>, skipped <b>{result.skippedDuplicates}</b> duplicates
                {result.errors.length > 0 && (
                  <>
                    , <b>{result.errors.length}</b> rows had problems
                  </>
                )}
                .
              </Alert>
              {result.errors.length > 0 && (
                <Table head={["Row", "Problem"]}>
                  {result.errors.map((e) => (
                    <tr key={e.row}>
                      <Td className="tabular-nums">{e.row}</Td>
                      <Td>{e.message}</Td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
          )}
        </Card>
        <Card title="File format">
          <ul className="space-y-1.5 text-sm text-ink-soft">
            <li>
              First row must be the header. <b>full_name</b> and <b>phone</b> are required.
            </li>
            <li>Optional: email, neet_roll_no, neet_score, neet_air, category, home_state, source, notes.</li>
            <li>category: GEN, EWS, OBC, SC or ST.</li>
            <li>source: {LEAD_SOURCES.filter((s) => s !== "REFERRAL_ASSOCIATE").join(", ")}.</li>
            <li>Referral leads must be added one at a time so the associate gets credit.</li>
            <li>Up to 5,000 rows per file. Save from Excel as “CSV UTF-8”.</li>
          </ul>
          <a href={templateHref} download="leads-template.csv" className="mt-4 inline-block text-sm font-medium text-brand-700 hover:underline">
            Download template
          </a>
        </Card>
      </div>
    </>
  );
}
