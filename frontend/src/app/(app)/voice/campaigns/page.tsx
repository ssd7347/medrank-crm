"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { CAMPAIGN_TONE, CampaignForm } from "@/components/campaign-form";
import { Alert, Badge, Button, Card, EmptyState, Loading, Modal, Table, Td } from "@/components/ui";
import { purposeLabel } from "@/components/voice";
import { formatDateTime, label } from "@/lib/format";
import type { Campaign } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

export default function CampaignsPage() {
  const router = useRouter();
  const { data, error, loading } = useApi<Campaign[]>("/api/voice/campaigns");
  const [creating, setCreating] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-sm text-ink-soft">
          A campaign calls everyone a reminder applies to, within calling hours, only if they agreed to AI calls, and never more than twice a day.
        </p>
        <Button onClick={() => setCreating(true)}>New campaign</Button>
      </div>
      {error && <Alert>{error}</Alert>}
      <Card>
        <div className="-m-4">
          {loading && !data ? (
            <Loading />
          ) : !data?.length ? (
            <EmptyState title="No campaigns yet">Start with deadline reminders to a small group of families who agreed to AI calls.</EmptyState>
          ) : (
            <Table head={["Campaign", "Kind", "Status", "People", "Created"]}>
              {data.map((c) => (
                <tr key={c.id}>
                  <Td>
                    <Link href={`/voice/campaigns/${c.id}`} className="font-medium text-brand-800 hover:underline">
                      {c.name}
                    </Link>
                  </Td>
                  <Td>{purposeLabel(c.purpose)}</Td>
                  <Td>
                    <Badge tone={CAMPAIGN_TONE[c.status]}>{label(c.status)}</Badge>
                  </Td>
                  <Td className="text-sm">
                    {c.counts.total} · {c.counts.pending} to call · {c.counts.done + c.counts.simulated} reached · {c.counts.skipped} skipped
                  </Td>
                  <Td className="whitespace-nowrap">{formatDateTime(c.createdAt)}</Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>
      <Modal open={creating} onClose={() => setCreating(false)} title="New campaign" wide>
        {creating && <CampaignForm onSaved={(d) => router.push(`/voice/campaigns/${d.campaign.id}`)} onCancel={() => setCreating(false)} />}
      </Modal>
    </div>
  );
}
