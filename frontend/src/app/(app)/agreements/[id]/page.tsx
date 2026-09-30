"use client";

import Link from "next/link";
import { useParams } from "next/navigation";

import { SIGN_METHOD_LABEL } from "@/components/agreements-tab";
import { Alert, Button, Loading } from "@/components/ui";
import { formatDateTime } from "@/lib/format";
import type { Agreement } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

export default function AgreementPage() {
  const { id } = useParams<{ id: string }>();
  const { data: a, error, loading } = useApi<Agreement>(`/api/agreements/${id}`);

  if (loading && !a) return <Loading />;
  if (error || !a) return <Alert>{error ?? "Agreement not found"}</Alert>;

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-4 flex items-center justify-between gap-3 print:hidden">
        <Link href={`/students/${a.studentId}?tab=agreements`} className="text-sm text-ink-soft hover:text-ink">
          ← {a.studentName}
        </Link>
        <Button variant="secondary" onClick={() => window.print()}>
          Print
        </Button>
      </div>
      <article className="rounded-xl border border-line bg-surface p-6 shadow-sm sm:p-10 print:border-0 print:p-0 print:shadow-none">
        <p className="text-xs font-medium tracking-wide text-ink-faint uppercase">{a.orgName}</p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">{a.title}</h1>
        <p className="mt-1 text-sm text-ink-soft">For {a.studentName}</p>
        <div className="mt-6 text-sm leading-relaxed whitespace-pre-wrap">{a.body}</div>

        <section className="mt-10 border-t border-line pt-5 text-sm">
          {a.status === "SIGNED" ? (
            <>
              <p className="font-medium">
                {a.signMethod === "PAPER" ? "Signed on paper" : "Accepted electronically"} by {a.signerName}
                {a.signerRelation && ` (${a.signerRelation.toLowerCase()})`}
              </p>
              <p className="mt-1 text-ink-soft">
                {formatDateTime(a.signedAt)} · {a.signMethod ? SIGN_METHOD_LABEL[a.signMethod] : ""}
              </p>
              {a.signMethod === "PORTAL_ACCEPTANCE" && (
                <p className="mt-1 text-xs text-ink-faint">Accepted from the family&apos;s own portal login by typing their name. This is not an Aadhaar e-signature.</p>
              )}
            </>
          ) : a.status === "PENDING" ? (
            <div className="grid gap-10 sm:grid-cols-2">
              <div>
                <div className="h-12 border-b border-ink-faint" />
                <p className="mt-1 text-xs text-ink-soft">Signature of student / parent / guardian</p>
              </div>
              <div>
                <div className="h-12 border-b border-ink-faint" />
                <p className="mt-1 text-xs text-ink-soft">Name and date</p>
              </div>
            </div>
          ) : (
            <p className="text-ink-soft">This document was cancelled before it was signed.</p>
          )}
          <p className="mt-6 text-xs text-ink-faint">
            Document reference {a.id} · text fingerprint {a.fingerprint} · issued {formatDateTime(a.issuedAt)}
          </p>
        </section>
      </article>
    </div>
  );
}
