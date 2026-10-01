"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { CAMPAIGN_TONE, CampaignForm } from "@/components/campaign-form";
import { Alert, Badge, Button, Card, DescList, EmptyState, Loading, Modal, Table, Td, type Tone } from "@/components/ui";
import { purposeLabel } from "@/components/voice";
import { api, errorMessage } from "@/lib/api";
import { formatDateTime, label } from "@/lib/format";
import { SKIP_LABEL, type CampaignDetail, type CampaignTarget, type SkipReason } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

const TARGET_TONE: Record<CampaignTarget["status"], Tone> = { PENDING: "blue", DONE: "green", SKIPPED: "gray", FAILED: "red", SIMULATED: "indigo" };

export default function CampaignPage() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, setData } = useApi<CampaignDetail>(`/api/voice/campaigns/${id}`);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [finishing, setFinishing] = useState(false);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Campaign not found"}</Alert>;
  const c = data.campaign;

  const act = async (path: string) => {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      setData(await api<CampaignDetail>(`/api/voice/campaigns/${id}/${path}`, { method: "POST" }));
      setFinishing(false);
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const runNow = async () => {
    setBusy(true);
    setActionError(null);
    try {
      const r = await api<{ placed: number }>("/api/voice/dispatch-now", { method: "POST" });
      setNotice(r.placed === 0 ? "Nobody could be called just now (see the reasons below)." : `${r.placed} call(s) placed.`);
      setData(await api<CampaignDetail>(`/api/voice/campaigns/${id}`));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const reasons = (m: Partial<Record<SkipReason, number>>) =>
    Object.entries(m).map(([k, v]) => (
      <li key={k} className="flex justify-between gap-2">
        <span>{SKIP_LABEL[k as SkipReason]}</span>
        <span className="tabular-nums">{v}</span>
      </li>
    ));

  return (
    <div className="space-y-6">
      <div className="text-sm">
        <Link href="/voice/campaigns" className="text-ink-soft hover:text-ink">
          ← Campaigns
        </Link>
      </div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">{c.name}</h2>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-ink-soft">
            <Badge tone={CAMPAIGN_TONE[c.status]}>{label(c.status)}</Badge>
            {purposeLabel(c.purpose)}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {c.status === "DRAFT" && (
            <>
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              <Button variant="secondary" loading={busy} onClick={() => act("audience")}>
                Find people again
              </Button>
            </>
          )}
          {c.status === "PAUSED" && (
            <Button variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
          )}
          {(c.status === "DRAFT" || c.status === "PAUSED") && (
            <Button loading={busy} onClick={() => act("start")} disabled={!c.scriptApproved}>
              {c.status === "DRAFT" ? "Start" : "Resume"}
            </Button>
          )}
          {c.status === "RUNNING" && (
            <>
              <Button variant="secondary" loading={busy} onClick={runNow}>
                Run now
              </Button>
              <Button variant="secondary" loading={busy} onClick={() => act("pause")}>
                Pause
              </Button>
            </>
          )}
          {c.status !== "DONE" && (
            <Button variant="ghost" onClick={() => setFinishing(true)}>
              Finish
            </Button>
          )}
        </div>
      </div>

      {!c.scriptApproved && c.status !== "DONE" && (
        <Alert tone="amber">
          No approved script for this kind of call yet. Read and approve one under <Link href="/voice/scripts" className="font-medium underline">Scripts</Link> before starting.
        </Alert>
      )}
      {actionError && <Alert>{actionError}</Alert>}
      {notice && <Alert tone="blue">{notice}</Alert>}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Settings">
          <DescList
            items={[
              ["Calling hours (IST)", `${c.windowStart.slice(0, 5)} – ${c.windowEnd.slice(0, 5)}`],
              [c.purpose === "MISSED_CALL_FOLLOWUP" ? "Looks back" : "Looks ahead", `${c.lookaheadDays} days`],
              ["Tries per person", String(c.maxAttempts)],
              ["Wait between tries", `${c.retryGapMin} min (and the other half of the day)`],
              ["Calls at the same time", String(c.maxConcurrent)],
              ["Started", c.startedAt ? formatDateTime(c.startedAt) : "Not yet"],
            ]}
          />
        </Card>
        <Card title={data.preview ? "If it ran right now" : "Result"}>
          {data.preview ? (
            <div className="space-y-3 text-sm">
              <p>
                <span className="text-2xl font-semibold tabular-nums">{data.preview.eligibleNow}</span> of {data.preview.checked} still to call could be called now.
              </p>
              {Object.keys(data.preview.waiting).length > 0 && (
                <div>
                  <p className="text-xs font-medium text-ink-soft">Will be called later</p>
                  <ul className="mt-1 space-y-0.5">{reasons(data.preview.waiting)}</ul>
                </div>
              )}
              {Object.keys(data.preview.skipped).length > 0 && (
                <div>
                  <p className="text-xs font-medium text-ink-soft">Will not be called</p>
                  <ul className="mt-1 space-y-0.5">{reasons(data.preview.skipped)}</ul>
                </div>
              )}
              {!data.preview.dndChecked && <p className="text-xs text-ink-faint">Do-not-disturb is not checked yet: no scrubbing provider is connected.</p>}
            </div>
          ) : (
            <p className="text-sm">
              {c.counts.done} reached · {c.counts.simulated} simulated · {c.counts.skipped} skipped · {c.counts.failed} failed
            </p>
          )}
        </Card>
      </div>

      <Card title={`People (${data.targets.length})`}>
        <div className="-m-4">
          {data.targets.length === 0 ? (
            <EmptyState title="Nobody fits this campaign right now">Try a longer look-ahead, or check the students&rsquo; records.</EmptyState>
          ) : (
            <Table head={["Name", "Number", "Status", "Tries", "Next try", ""]}>
              {data.targets.map((t) => (
                <tr key={t.id}>
                  <Td>
                    <Link href={t.studentId ? `/students/${t.studentId}?tab=voice` : `/leads/${t.leadId}`} className="font-medium text-brand-800 hover:underline">
                      {t.name ?? "—"}
                    </Link>
                    {t.recipient === "PARENT" && <span className="block text-xs text-ink-faint">Parent&rsquo;s number</span>}
                  </Td>
                  <Td className="whitespace-nowrap">{t.phone}</Td>
                  <Td>
                    <Badge tone={TARGET_TONE[t.status]}>{t.status === "SIMULATED" ? "Simulated" : label(t.status)}</Badge>
                    {t.skipReason && <span className="block text-xs text-ink-faint">{SKIP_LABEL[t.skipReason]}</span>}
                  </Td>
                  <Td className="tabular-nums">{t.attempts}</Td>
                  <Td className="whitespace-nowrap">{t.status === "PENDING" && t.nextAttemptAt ? formatDateTime(t.nextAttemptAt) : "—"}</Td>
                  <Td className="text-right">
                    {t.lastCallId && (
                      <Link href={`/voice/calls/${t.lastCallId}`} className="text-sm font-medium text-brand-800 hover:underline">
                        Last call
                      </Link>
                    )}
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      </Card>

      <Modal open={editing} onClose={() => setEditing(false)} title="Edit campaign" wide>
        {editing && (
          <CampaignForm
            existing={c}
            onSaved={(d) => {
              setData(d);
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        )}
      </Modal>
      <Modal open={finishing} onClose={() => setFinishing(false)} title="Finish this campaign?">
        <p className="text-sm text-ink-soft">Anyone not called yet will not be called. This cannot be undone.</p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setFinishing(false)}>
            Cancel
          </Button>
          <Button variant="danger" loading={busy} onClick={() => act("finish")}>
            Finish
          </Button>
        </div>
      </Modal>
    </div>
  );
}
