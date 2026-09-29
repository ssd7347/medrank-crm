"use client";

import { useRouter } from "next/navigation";

import { LeadForm } from "@/components/lead-form";
import { Card, PageHeader } from "@/components/ui";
import { api } from "@/lib/api";
import type { Lead } from "@/lib/types";

export default function NewLeadPage() {
  const router = useRouter();
  return (
    <>
      <PageHeader title="New lead" subtitle="Only name, phone and source are required. Add NEET details when you have them." />
      <Card>
        <LeadForm
          submitLabel="Create lead"
          onCancel={() => router.back()}
          onSubmit={async (req) => {
            const lead = await api<Lead>("/api/leads", { body: req });
            router.push(`/leads/${lead.id}`);
          }}
        />
      </Card>
    </>
  );
}
