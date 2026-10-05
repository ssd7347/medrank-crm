"use client";

import { Alert, Badge, Loading, PageHeader } from "@/components/ui";
import type { Integration } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

export default function IntegrationsPage() {
  const { data, error, loading } = useApi<Integration[]>("/api/integrations");
  const connected = (data ?? []).filter((i) => i.state === "CONNECTED").length;

  return (
    <>
      <PageHeader title="Connected services" subtitle="Which outside services are connected, and what the CRM does in the meantime." />
      {error && <Alert>{error}</Alert>}
      {loading && !data ? (
        <Loading />
      ) : data ? (
        <>
          <p className="mb-4 text-sm text-ink-soft">
            {connected} of {data.length} connected. Everything works without them, but the manual steps below stay with your staff until each one is connected.
          </p>
          <ul className="grid gap-3 lg:grid-cols-2">
            {data.map((i) => (
              <li key={i.key} className="group relative overflow-hidden rounded-lg border border-line bg-surface p-5 shadow-card transition duration-300 hover:border-brand-300 hover:shadow-lift">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h2 className="text-sm font-semibold">{i.name}</h2>
                    <p className="text-xs text-ink-faint">{i.module}</p>
                  </div>
                  <Badge tone={i.state === "CONNECTED" ? "green" : "gray"}>{i.state === "CONNECTED" ? "Connected" : "Not connected"}</Badge>
                </div>
                {i.state !== "CONNECTED" && (
                  <dl className="mt-3 space-y-2 text-sm">
                    <div>
                      <dt className="text-xs text-ink-faint">What happens today</dt>
                      <dd>{i.today}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-faint">Needed to connect</dt>
                      <dd className="text-ink-soft">{i.needs}</dd>
                    </div>
                  </dl>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </>
  );
}
