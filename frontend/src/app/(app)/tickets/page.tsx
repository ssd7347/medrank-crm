"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { TicketForm, TicketTable } from "@/components/tickets";
import { Alert, Button, Card, Loading, Modal, PageHeader, cx } from "@/components/ui";
import type { TicketView } from "@/lib/types-operations";
import { useApi } from "@/lib/use-api";

export default function TicketsPage() {
  const router = useRouter();
  const [openOnly, setOpenOnly] = useState(true);
  const [mine, setMine] = useState(false);
  const [adding, setAdding] = useState(false);
  const { data, error, loading } = useApi<TicketView[]>("/api/tickets", { openOnly, mine });
  const overdue = (data ?? []).filter((t) => t.overdue).length;

  const chip = (active: boolean, text: string, onClick: () => void) => (
    <button onClick={onClick} className={cx("shrink-0 rounded-full border px-3 py-1 text-xs", active ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}>
      {text}
    </button>
  );

  return (
    <>
      <PageHeader title="Helpdesk" subtitle="Routine questions and complaints. Serious disputes belong in the grievance register." actions={<Button onClick={() => setAdding(true)}>New ticket</Button>} />
      <div className="mb-3 flex gap-1.5 overflow-x-auto">
        {chip(openOnly, "Open", () => setOpenOnly(true))}
        {chip(!openOnly, "All", () => setOpenOnly(false))}
        {chip(mine, "Assigned to me", () => setMine((m) => !m))}
      </div>
      {overdue > 0 && (
        <div className="mb-3">
          <Alert>
            {overdue} ticket{overdue === 1 ? " is" : "s are"} past the response deadline.
          </Alert>
        </div>
      )}
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">{loading && !data ? <Loading /> : <TicketTable tickets={data ?? []} />}</div>
      </Card>
      <Modal open={adding} onClose={() => setAdding(false)} title="New ticket" wide>
        {adding && <TicketForm onDone={(t) => router.push(`/tickets/${t.id}`)} />}
      </Modal>
    </>
  );
}
