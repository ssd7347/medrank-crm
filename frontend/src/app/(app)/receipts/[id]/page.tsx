"use client";

import { useParams } from "next/navigation";

import { Alert, Button, Loading } from "@/components/ui";
import { formatDate, formatRupees, label } from "@/lib/format";
import type { Receipt } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

/** Printable receipt. The app's navigation is hidden when printing (print:hidden in the layout). */
export default function ReceiptPage() {
  const { id } = useParams<{ id: string }>();
  const { data: r, error, loading } = useApi<Receipt>(`/api/payments/${id}/receipt`);

  if (loading && !r) return <Loading />;
  if (error || !r) return <Alert>{error ?? "Receipt not found"}</Alert>;

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-4 flex justify-end gap-2 print:hidden">
        <Button onClick={() => window.print()}>Print / save as PDF</Button>
      </div>
      <article className="relative rounded-lg border border-line bg-surface p-6 shadow-card print:border-0 print:shadow-none">
        {r.voided && (
          <div className="pointer-events-none absolute inset-0 grid place-items-center">
            <span className="-rotate-12 rounded-lg border-4 border-red-600 px-6 py-2 text-4xl font-bold text-red-600 opacity-70">VOID</span>
          </div>
        )}
        <header className="flex items-start justify-between gap-4 border-b border-line pb-4">
          <div>
            <p className="text-lg font-semibold">{r.orgName}</p>
            <p className="text-sm text-ink-soft">Payment receipt</p>
          </div>
          <div className="text-right">
            <p className="font-mono text-sm font-semibold">{r.receiptNo}</p>
            <p className="text-sm text-ink-soft">{formatDate(r.paidOn)}</p>
          </div>
        </header>
        <dl className="mt-4 space-y-2 text-sm">
          <Row k="Received from" v={`${r.studentName} (${r.studentPhone})`} />
          <Row k="Towards" v={r.planName} />
          <Row k="Payment method" v={`${label(r.method)}${r.reference ? ` · ${r.reference}` : ""}`} />
        </dl>
        <div className="mt-5 rounded-lg bg-muted p-4 text-center">
          <p className="text-xs text-ink-faint">Amount received</p>
          <p className="text-3xl font-semibold tabular-nums">{formatRupees(r.amount)}</p>
        </div>
        <dl className="mt-4 space-y-1 text-sm">
          <Row k="Plan total (after discount)" v={formatRupees(r.planNet)} />
          <Row k="Paid to date" v={formatRupees(r.paidToDate)} />
          <Row k="Balance" v={formatRupees(r.balance)} />
        </dl>
        {r.voided && <p className="mt-4 text-sm text-red-700">This receipt was voided: {r.voidReason}</p>}
        <footer className="mt-8 flex items-end justify-between text-xs text-ink-faint">
          <span>Received by {r.receivedBy ?? "—"}</span>
          <span>Consultancy service fee. Not a college fee receipt.</span>
        </footer>
      </article>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-ink-soft">{k}</dt>
      <dd className="text-right font-medium">{v}</dd>
    </div>
  );
}
