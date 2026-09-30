"use client";

import type { LeadScore, RiskScore } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

import { Badge, type Tone } from "./ui";

export const BAND_TONE: Record<LeadScore["band"], Tone> = { HOT: "red", WARM: "amber", COLD: "gray" };
export const RISK_TONE: Record<RiskScore["risk"], Tone> = { HIGH: "red", MEDIUM: "amber", LOW: "green" };
const BAND_LABEL: Record<LeadScore["band"], string> = { HOT: "Hot", WARM: "Warm", COLD: "Cold" };
const RISK_LABEL: Record<RiskScore["risk"], string> = { HIGH: "High drop-off risk", MEDIUM: "Some drop-off risk", LOW: "Low drop-off risk" };

/** A score is never shown without its reasons: click to see exactly what it is based on. */
function Explained({ summary, reasons, empty }: { summary: React.ReactNode; reasons: string[]; empty: string }) {
  return (
    <details className="relative inline-block text-left">
      <summary className="cursor-pointer list-none [&::-webkit-details-marker]:hidden">{summary}</summary>
      <div className="absolute left-0 z-30 mt-1 w-64 rounded-lg border border-line bg-surface p-3 text-xs shadow-lg">
        {reasons.length ? (
          <ul className="list-disc space-y-1 pl-4 text-ink">
            {reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        ) : (
          <p className="text-ink-soft">{empty}</p>
        )}
        <p className="mt-2 border-t border-line pt-2 text-ink-faint">Worked out from simple rules, not a guarantee. Use your own judgement.</p>
      </div>
    </details>
  );
}

export function LeadScoreView({ score }: { score: LeadScore }) {
  return (
    <Explained
      summary={
        <Badge tone={BAND_TONE[score.band]}>
          {BAND_LABEL[score.band]} · {score.score}
        </Badge>
      }
      reasons={score.reasons}
      empty="Nothing notable yet; this is the starting score."
    />
  );
}

export function LeadScoreBadge({ leadId, version }: { leadId: number | string; version?: number }) {
  const { data } = useApi<LeadScore>(`/api/leads/${leadId}/score`, { v: version ?? 0 });
  return data ? <LeadScoreView score={data} /> : null;
}

export function RiskView({ risk }: { risk: RiskScore }) {
  return <Explained summary={<Badge tone={RISK_TONE[risk.risk]}>{RISK_LABEL[risk.risk]}</Badge>} reasons={risk.reasons} empty="No warning signs: fees, tickets and calls all look fine." />;
}

export function RiskBadge({ studentId, version }: { studentId: number | string; version?: number }) {
  const { data } = useApi<RiskScore>(`/api/students/${studentId}/risk`, { v: version ?? 0 });
  return data ? <RiskView risk={data} /> : null;
}
