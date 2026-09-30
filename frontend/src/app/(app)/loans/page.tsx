"use client";

import Link from "next/link";
import { useState } from "react";

import { LOAN_TONE, LoanDeadline, LoanForm, PartnerForm } from "@/components/loans";
import { Alert, Badge, Button, Card, Checkbox, EmptyState, Loading, Modal, PageHeader, Table, Td } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { formatRupees } from "@/lib/format";
import { LOAN_STATUS_LABEL, type Loan, type LoanPartner } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

export default function LoanDeskPage() {
  const { hasRole } = useAuth();
  const canManageLenders = hasRole("SUPER_ADMIN", "LOAN_DESK");
  const [all, setAll] = useState(false);
  const loans = useApi<Loan[]>("/api/loans", { all });
  const partners = useApi<LoanPartner[]>("/api/loan-partners");
  const [editing, setEditing] = useState<Loan | null>(null);
  const [partner, setPartner] = useState<LoanPartner | "new" | null>(null);
  const risky = (loans.data ?? []).filter((l) => l.risk !== "NONE").length;

  return (
    <>
      <PageHeader title="Loan desk" subtitle="Education-loan applications tracked against the date the money is needed by. We refer and follow up; the lender decides." />
      {risky > 0 && (
        <div className="mb-4">
          <Alert>
            {risky} {risky === 1 ? "application is" : "applications are"} still waiting on the lender with the deadline a week away or less.
          </Alert>
        </div>
      )}
      <div className="space-y-6">
        <Card title="Applications" actions={<Checkbox label="Include rejected, disbursed and withdrawn" checked={all} onChange={(e) => setAll(e.target.checked)} />}>
          <div className="-m-4">
            {loans.error && (
              <div className="p-3">
                <Alert>{loans.error}</Alert>
              </div>
            )}
            {loans.loading && !loans.data ? (
              <Loading />
            ) : !loans.data?.length ? (
              <EmptyState title="No loan applications">Start one from a student&apos;s Loans tab.</EmptyState>
            ) : (
              <Table head={["Student", "Lender", "Amount", "Status", "Money needed by", "Handled by", ""]}>
                {loans.data.map((l) => (
                  <tr key={l.id}>
                    <Td>
                      <Link href={`/students/${l.studentId}?tab=loans`} className="font-medium text-brand-800 hover:underline">
                        {l.studentName}
                      </Link>
                      <span className="block text-xs text-ink-soft tabular-nums">{l.studentPhone}</span>
                    </Td>
                    <Td>{l.partnerName}</Td>
                    <Td className="tabular-nums">
                      {formatRupees(l.amountRequested)}
                      {l.amountSanctioned !== null && <span className="block text-xs text-emerald-700">sanctioned {formatRupees(l.amountSanctioned)}</span>}
                    </Td>
                    <Td>
                      <Badge tone={LOAN_TONE[l.status]}>{LOAN_STATUS_LABEL[l.status]}</Badge>
                    </Td>
                    <Td>
                      <LoanDeadline loan={l} />
                    </Td>
                    <Td>{l.handledBy?.fullName ?? "—"}</Td>
                    <Td className="text-right">
                      <Button size="sm" variant="ghost" onClick={() => setEditing(l)}>
                        Update
                      </Button>
                    </Td>
                  </tr>
                ))}
              </Table>
            )}
          </div>
        </Card>

        <Card title="Lenders we refer to" actions={canManageLenders && <Button size="sm" onClick={() => setPartner("new")}>Add lender</Button>}>
          <div className="-m-4">
            {partners.loading && !partners.data ? (
              <Loading />
            ) : !partners.data?.length ? (
              <EmptyState title="No lenders yet">Add the 2–3 NBFCs or banks you work with.</EmptyState>
            ) : (
              <Table head={["Lender", "Interest (reference only)", "Up to", "Contact", ""]}>
                {partners.data.map((p) => (
                  <tr key={p.id}>
                    <Td>
                      <span className="font-medium">{p.name}</span>
                      {!p.active && <span className="ml-2 align-middle"><Badge>Not in use</Badge></span>}
                      {p.eligibility && <span className="block text-xs text-ink-soft">{p.eligibility}</span>}
                    </Td>
                    <Td>{p.interestInfo ?? "—"}</Td>
                    <Td className="tabular-nums">{p.maxAmount === null ? "—" : formatRupees(p.maxAmount)}</Td>
                    <Td>
                      {p.contactName ?? "—"}
                      {p.contactPhone && <span className="block text-xs text-ink-soft tabular-nums">{p.contactPhone}</span>}
                    </Td>
                    <Td className="text-right">
                      {canManageLenders && (
                        <Button size="sm" variant="ghost" onClick={() => setPartner(p)}>
                          Edit
                        </Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </Table>
            )}
          </div>
        </Card>
        <p className="text-xs text-ink-faint">Interest rates and limits are for reference only. The lender sets the final terms; the consultancy does not lend or guarantee approval.</p>
      </div>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={`Update loan · ${editing?.studentName ?? ""}`}>
        {editing && (
          <LoanForm
            studentId={editing.studentId}
            loan={editing}
            partners={partners.data ?? []}
            onDone={() => {
              setEditing(null);
              loans.reload();
            }}
          />
        )}
      </Modal>
      <Modal open={partner !== null} onClose={() => setPartner(null)} title={partner === "new" ? "Add lender" : "Edit lender"}>
        {partner !== null && (
          <PartnerForm
            partner={partner === "new" ? undefined : partner}
            onDone={() => {
              setPartner(null);
              partners.reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}
