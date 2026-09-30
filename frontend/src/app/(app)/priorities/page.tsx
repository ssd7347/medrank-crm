"use client";

import { LeadPriorityList, SessionList, StudentRiskList } from "@/components/priorities";
import { Alert, Card, Loading, PageHeader } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import type { Priorities, Session } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

export default function PrioritiesPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("SUPER_ADMIN");
  const seesStudents = hasRole("SUPER_ADMIN", "COUNSELLOR");
  const { data, error, loading } = useApi<Priorities>("/api/priorities");
  const sessions = useApi<Session[]>(seesStudents ? "/api/sessions/mine" : null);

  return (
    <>
      <PageHeader
        title="What to do next"
        subtitle={isAdmin ? "Across the whole team: leads that need a nudge, the most promising leads, and students who may drop off." : "Your leads that need a nudge, your most promising leads, and your students who may drop off."}
      />
      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {loading && !data ? (
        <Loading />
      ) : data ? (
        <div className="grid gap-6 lg:grid-cols-2">
          <Card title={`Needs action (${data.leads.length})`}>
            <LeadPriorityList leads={data.leads} showAction />
          </Card>
          <Card title={`Hot leads (${data.hotLeads.length})`}>
            <LeadPriorityList leads={data.hotLeads} showAction={false} />
          </Card>
          {seesStudents && (
            <>
              <Card title={`Students at risk of dropping off (${data.students.length})`}>
                <StudentRiskList students={data.students} />
              </Card>
              <Card title="My sessions">{sessions.loading && !sessions.data ? <Loading /> : <SessionList sessions={sessions.data ?? []} />}</Card>
            </>
          )}
        </div>
      ) : null}
      <p className="mt-6 text-xs text-ink-faint">
        Scores come from simple, visible rules (how fast we responded, whether calls connect, overdue follow-ups, fees and tickets). Click any score to see its
        reasons.
      </p>
    </>
  );
}
