"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Button, Card, Loading, Modal, cx } from "@/components/ui";
import { VOICE_STATE_CHANGED, purposeLabel } from "@/components/voice";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatRupees, label } from "@/lib/format";
import type { VoiceMetrics, VoiceOverview } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

export default function VoiceOverviewPage() {
  const { hasRole } = useAuth();
  const admin = hasRole("SUPER_ADMIN");
  const { data, error, loading, setData } = useApi<VoiceOverview>("/api/voice/overview");
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Could not load"}</Alert>;

  const setPaused = async (paused: boolean) => {
    setSaving(true);
    setActionError(null);
    try {
      setData(await api<VoiceOverview>("/api/voice/pause-all", { body: { paused } }));
      setConfirming(false);
      // The banner above the tabs reads the same endpoint; tell it to refresh.
      window.dispatchEvent(new Event(VOICE_STATE_CHANGED));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Calling" value={!data.enabled ? "Switched off" : data.paused ? "Paused" : data.live ? "Live" : "Simulated"} tone={data.paused || !data.enabled ? "red" : data.live ? "green" : "amber"} />
        <Stat label="Calling hours (IST)" value={`${data.windowStart.slice(0, 5)} – ${data.windowEnd.slice(0, 5)}`} />
        <Stat label="Call-backs waiting" value={String(data.openCallbacks)} href="/voice/callbacks" />
        <Stat label="Overdue call-backs" value={String(data.overdueCallbacks)} tone={data.overdueCallbacks > 0 ? "red" : undefined} href="/voice/callbacks" />
      </div>

      {admin && (
        <Card title="Pause all calling">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="max-w-2xl text-sm text-ink-soft">
              {data.paused
                ? "Nothing is being dialled. Incoming callers hear the normal greeting instead of the AI agent."
                : "Stops every campaign at once and sends incoming callers to the normal greeting. Use it if something looks wrong."}
            </p>
            {data.paused ? (
              <Button loading={saving} onClick={() => setPaused(false)}>
                Resume calling
              </Button>
            ) : (
              <Button variant="danger" onClick={() => setConfirming(true)}>
                Pause all
              </Button>
            )}
          </div>
          {actionError && (
            <div className="mt-3">
              <Alert>{actionError}</Alert>
            </div>
          )}
        </Card>
      )}

      {admin ? <MetricsCard /> : <CounsellorHelp />}

      <Modal open={confirming} onClose={() => setConfirming(false)} title="Pause all AI calling?">
        <p className="text-sm text-ink-soft">
          No campaign will dial anyone until you resume. {data.runningCampaigns > 0 && `${data.runningCampaigns} campaign(s) are running now.`}
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirming(false)}>
            Cancel
          </Button>
          <Button variant="danger" loading={saving} onClick={() => setPaused(true)}>
            Pause all
          </Button>
        </div>
      </Modal>
    </div>
  );
}

function CounsellorHelp() {
  return (
    <Card title="Your part">
      <ul className="list-disc space-y-1 pl-5 text-sm text-ink-soft">
        <li>When the AI agent cannot or must not help (seat decisions, refunds, distress, or the caller asks for a person), it books a call-back. Your call-backs are under &ldquo;Call-backs&rdquo;.</li>
        <li>Record each family&rsquo;s consent on the student&rsquo;s &ldquo;AI calls&rdquo; tab. Nobody is called by the AI without it.</li>
        <li>Under &ldquo;Calls&rdquo; you can read the transcript of any AI call with your students, and flag one that went wrong.</li>
      </ul>
    </Card>
  );
}

function MetricsCard() {
  const [days, setDays] = useState(30);
  const { data, error, loading } = useApi<VoiceMetrics>("/api/voice/metrics", { days });
  return (
    <Card
      title="How the calls are going"
      actions={
        <div className="flex gap-1">
          {[7, 30, 90].map((d) => (
            <button
              key={d}
              onClick={() => setDays(d)}
              className={cx("rounded-md border px-2.5 py-1.5 font-medium transition-colors text-xs", d === days ? "border-brand-600 bg-brand-600 text-on-brand" : "border-line hover:bg-muted")}
            >
              {d} days
            </button>
          ))}
        </div>
      }
    >
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : data ? (
        <div className="space-y-5">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="Calls" value={String(data.calls)} />
            <Stat label="Answered" value={`${data.connectRatePercent}%`} sub={`${data.connected} of ${data.connected + data.noAnswer} rung`} />
            <Stat label="Average length" value={data.avgDurationSec === null ? "—" : `${Math.round(data.avgDurationSec / 6) / 10} min`} />
            <Stat label="Handed to a person" value={`${data.handoffRatePercent}%`} />
            <Stat label="Asked to stop" value={String(data.optOuts)} tone={data.optOuts >= 5 ? "red" : undefined} />
            <Stat label="Simulated" value={String(data.simulated)} sub="not dialled" />
          </div>
          <div className="grid gap-5 md:grid-cols-3">
            <Breakdown title="Outcomes" rows={data.outcomes} />
            <Breakdown title="Why handed over" rows={data.handoffs} />
            <Breakdown title="Kinds of call" rows={data.purposes} purpose />
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="Look-ups by the agent" value={String(data.toolCalls)} />
            <Stat label="Look-up time (typical / slowest 5%)" value={data.toolP50Ms === null ? "—" : `${data.toolP50Ms} / ${data.toolP95Ms} ms`} tone={data.toolP95Ms !== null && data.toolP95Ms > 2000 ? "red" : undefined} />
            <Stat label="Estimated cost, this month" value={formatRupees(data.costThisMonthInr)} sub={`limit ${formatRupees(data.monthlyCapInr)}`} tone={data.costThisMonthInr >= data.monthlyCapInr ? "red" : undefined} />
            <Stat label="Flagged as wrong" value={String(data.flagged)} href="/voice/calls?flagged=1" />
          </div>
          <p className="text-xs text-ink-faint">Cost is an estimate from connected minutes and the per-minute rate in the server settings, not a provider invoice.</p>
        </div>
      ) : null}
    </Card>
  );
}

function Breakdown({ title, rows, purpose }: { title: string; rows: Record<string, number>; purpose?: boolean }) {
  const entries = Object.entries(rows).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...entries.map((e) => e[1]));
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-ink-soft">{title}</p>
      {entries.length === 0 ? (
        <p className="text-sm text-ink-faint">Nothing yet</p>
      ) : (
        <ul className="space-y-1.5">
          {entries.map(([k, v]) => (
            <li key={k} className="text-sm">
              <div className="flex justify-between gap-2">
                <span className="truncate">{purpose ? purposeLabel(k) : label(k)}</span>
                <span className="tabular-nums text-ink-soft">{v}</span>
              </div>
              <div className="mt-0.5 h-1.5 rounded-full bg-muted">
                <div className="h-1.5 rounded-full bg-brand-600" style={{ width: `${(v / max) * 100}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Stat({ label: text, value, sub, tone, href }: { label: string; value: string; sub?: string; tone?: "red" | "green" | "amber"; href?: string }) {
  const body = (
    <div className="h-full rounded-lg border border-line bg-surface p-4 shadow-card transition duration-300 hover:border-brand-300">
      <p className="eyebrow text-ink-faint">{text}</p>
      <p className={cx("mt-2 font-display text-[1.75rem] leading-none font-semibold", tone === "red" && "text-red-700", tone === "green" && "text-emerald-700", tone === "amber" && "text-amber-700")}>{value}</p>
      {sub && <p className="text-xs text-ink-faint">{sub}</p>}
    </div>
  );
  return href ? (
    <Link href={href} className="block hover:opacity-90">
      {body}
    </Link>
  ) : (
    body
  );
}
