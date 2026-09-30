"use client";

import Link from "next/link";

import { formatDateTime } from "@/lib/format";
import type { Priorities, PriorityLead, Session } from "@/lib/types-advanced";

import { LeadStatusBadge } from "./badges";
import { LeadScoreView, RiskView } from "./score-badge";
import { EmptyState } from "./ui";

export function LeadPriorityList({ leads, showAction }: { leads: PriorityLead[]; showAction: boolean }) {
  if (!leads.length) return <EmptyState title="Nothing here">{showAction ? "No lead needs a nudge right now." : "No lead is scoring as hot yet."}</EmptyState>;
  return (
    <ul className="-my-2 divide-y divide-line">
      {leads.map((l) => (
        <li key={l.id} className="flex items-start justify-between gap-3 py-2.5">
          <div className="min-w-0">
            <Link href={`/leads/${l.id}`} className="text-sm font-medium text-brand-800 hover:underline">
              {l.fullName}
            </Link>
            <p className="text-xs text-ink-soft tabular-nums">
              {l.phone}
              {l.assignedTo && ` · ${l.assignedTo.fullName}`}
            </p>
            {showAction && l.score.nextAction && <p className="mt-0.5 text-xs font-medium text-amber-800">{l.score.nextAction}</p>}
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1">
            <LeadScoreView score={l.score} />
            <LeadStatusBadge status={l.status} />
          </div>
        </li>
      ))}
    </ul>
  );
}

export function StudentRiskList({ students }: { students: Priorities["students"] }) {
  if (!students.length) return <EmptyState title="No student looks at risk">Fees, tickets and calls all look fine.</EmptyState>;
  return (
    <ul className="-my-2 divide-y divide-line">
      {students.map((s) => (
        <li key={s.id} className="flex items-start justify-between gap-3 py-2.5">
          <div className="min-w-0">
            <Link href={`/students/${s.id}`} className="text-sm font-medium text-brand-800 hover:underline">
              {s.fullName}
            </Link>
            <p className="text-xs text-ink-soft">{s.risk.reasons.join(" · ")}</p>
          </div>
          <div className="shrink-0">
            <RiskView risk={s.risk} />
          </div>
        </li>
      ))}
    </ul>
  );
}

export function SessionList({ sessions }: { sessions: Session[] }) {
  const upcoming = sessions.filter((s) => s.status === "SCHEDULED");
  if (!upcoming.length) return <EmptyState title="No sessions scheduled">Schedule one from a student&apos;s “Sessions & calls” tab.</EmptyState>;
  return (
    <ul className="-my-2 divide-y divide-line">
      {upcoming.map((s) => (
        <li key={s.id} className="flex items-center justify-between gap-3 py-2.5">
          <div className="min-w-0">
            <Link href={`/students/${s.studentId}?tab=sessions`} className="text-sm font-medium text-brand-800 hover:underline">
              {s.studentName}
            </Link>
            <p className="text-xs text-ink-soft">
              {formatDateTime(s.scheduledAt)} · {s.topic}
            </p>
          </div>
          {s.meetingUrl && (
            <a href={s.meetingUrl} target="_blank" rel="noopener noreferrer" className="shrink-0 rounded-lg bg-brand-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-brand-700">
              Join
            </a>
          )}
        </li>
      ))}
    </ul>
  );
}
