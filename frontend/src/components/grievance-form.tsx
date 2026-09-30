"use client";

import { useState } from "react";

import { api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import { CHANNELS, GRIEVANCE_CATEGORIES, type Channel, type GrievanceView } from "@/lib/types-operations";

import { Alert, Button, Field, Input, Select, Textarea } from "./ui";

/** Logs a formal grievance, optionally from a ticket (which is then closed with a pointer to it). */
export function GrievanceForm({
  ticketId,
  studentId,
  initialDescription = "",
  initialChannel = "PHONE",
  onDone,
}: {
  ticketId?: number;
  studentId?: number;
  initialDescription?: string;
  initialChannel?: Channel;
  onDone: (g: GrievanceView) => void;
}) {
  const [v, setV] = useState({
    complainantName: "",
    complainantPhone: "",
    category: "FEE_REFUND",
    description: initialDescription,
    amountInDispute: "",
    receivedVia: initialChannel as string,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setV({ ...v, [k]: e.target.value });
  const linked = ticketId !== undefined || studentId !== undefined;

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setSaving(true);
        setError(null);
        try {
          onDone(
            await api<GrievanceView>("/api/grievances", {
              body: {
                ticketId: ticketId ?? null,
                studentId: studentId ?? null,
                complainantName: v.complainantName || null,
                complainantPhone: v.complainantPhone || null,
                category: v.category,
                description: v.description,
                amountInDispute: v.amountInDispute ? Number(v.amountInDispute) : null,
                receivedVia: v.receivedVia,
              },
            }),
          );
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setSaving(false);
        }
      }}
      className="space-y-4"
    >
      {error && <Alert>{error}</Alert>}
      <Alert tone="amber">
        Use the grievance register for disputes with legal or financial exposure: refund disputes, allotment disputes, serious complaints. Every step is permanently recorded.
      </Alert>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Complainant name" required={!linked} hint={linked ? "Defaults to the student" : undefined}>
          {(id) => <Input id={id} required={!linked} maxLength={120} value={v.complainantName} onChange={set("complainantName")} />}
        </Field>
        <Field label="Phone">{(id) => <Input id={id} type="tel" maxLength={20} value={v.complainantPhone} onChange={set("complainantPhone")} />}</Field>
        <Field label="Category">{(id) => <Select id={id} value={v.category} onChange={set("category")} options={GRIEVANCE_CATEGORIES} labelFor={label} />}</Field>
        <Field label="Received via">{(id) => <Select id={id} value={v.receivedVia} onChange={set("receivedVia")} options={CHANNELS} labelFor={label} />}</Field>
        <Field label="Amount in dispute (₹)">{(id) => <Input id={id} type="number" inputMode="decimal" min={0} value={v.amountInDispute} onChange={set("amountInDispute")} />}</Field>
      </div>
      <Field label="What is the complaint?" required>
        {(id) => <Textarea id={id} required rows={4} maxLength={4000} value={v.description} onChange={set("description")} />}
      </Field>
      <Button type="submit" loading={saving}>
        Log grievance
      </Button>
    </form>
  );
}
