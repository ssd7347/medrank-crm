"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, DescList, Loading } from "@/components/ui";
import type { ProviderSetup, Readiness, ReadinessState } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

const STATE: Record<ReadinessState, { tone: "green" | "amber" | "red"; label: string }> = {
  OK: { tone: "green", label: "Ready" },
  WARN: { tone: "amber", label: "Check" },
  FAIL: { tone: "red", label: "Fix first" },
};

/** The checks of docs/first-real-call.md, run by the CRM on request. It places no call and changes nothing. */
function ReadinessCard() {
  const { data, error, loading, reload } = useApi<Readiness>("/api/voice/readiness");
  const fails = data?.checks.filter((c) => c.state === "FAIL").length ?? 0;
  const warns = data?.checks.filter((c) => c.state === "WARN").length ?? 0;
  return (
    <Card
      title="Ready for a real call?"
      actions={
        <Button size="sm" variant="secondary" loading={loading} onClick={reload}>
          Check again
        </Button>
      }
    >
      <p className="mb-4 text-sm text-ink-soft">
        Reads the settings and asks Plivo, the public address, the speech engine and the brain whether they are ready. It places no call and changes nothing.
      </p>
      {loading && !data ? (
        <Loading />
      ) : error ? (
        <Alert>{error}</Alert>
      ) : data ? (
        <div className="space-y-4">
          {data.ready ? (
            <Alert tone="green">
              Nothing blocks a real call{warns > 0 ? `; ${warns} ${warns === 1 ? "thing" : "things"} to look at below` : ""}.
            </Alert>
          ) : (
            <Alert>
              Not ready: {fails} {fails === 1 ? "thing" : "things"} to fix first.
            </Alert>
          )}
          <ul className="-my-2 divide-y divide-line">
            {data.checks.map((c) => (
              <li key={c.key} className="flex flex-col gap-1 py-2.5 text-sm sm:flex-row sm:items-start sm:gap-3">
                <span className="sm:w-24 sm:shrink-0">
                  <Badge tone={STATE[c.state].tone}>{STATE[c.state].label}</Badge>
                </span>
                <span className="min-w-0">
                  <span className="font-medium text-ink">{c.title}</span>
                  <span className="block break-words text-ink-soft">{c.detail}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-xs text-ink-faint">Checked at {new Date(data.checkedAt).toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata" })}.</p>
        </div>
      ) : null}
    </Card>
  );
}

export default function ProviderSetupPage() {
  const { data, error, loading } = useApi<ProviderSetup>("/api/voice/provider-setup");
  const [copied, setCopied] = useState(false);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Could not load"}</Alert>;
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const tools = JSON.stringify(
    data.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.input_schema })),
    null,
    2,
  );

  return (
    <div className="space-y-6">
      <ReadinessCard />
      <Card title="What is connected">
        <DescList
          items={[
            ["Voice platform", <span key="p" className="flex items-center gap-2">{data.platform} <Badge tone={data.live ? "green" : "amber"}>{data.live ? "Live" : "Simulated"}</Badge></span>],
            ["Request signing secret", <Badge key="s" tone={data.signingConfigured ? "green" : "red"}>{data.signingConfigured ? "Set" : "Not set: platform requests are refused"}</Badge>],
          ]}
        />
      </Card>
      <Card title="To connect the phone line">
        <ol className="list-decimal space-y-2 pl-5 text-sm text-ink-soft">
          <li>The client opens a Plivo account in its own name (India region), completes KYC and rents an Indian number.</li>
          <li>
            On the server, set <code>VOICE_PLATFORM=plivo</code>, the <code>PLIVO_*</code> keys, <code>VOICE_HMAC_SECRET</code> and <code>VOICE_DESK_NUMBER</code>, and start the speech
            engine and the gateway. Only the gateway goes on a public HTTPS address (see <code>docs/first-real-call.md</code> in the code).
          </li>
          <li>Approve a script whose opening says the call may be recorded, record consent, then use &quot;Ready for a real call?&quot; above until nothing needs fixing.</li>
          <li>Try the test console and a one-contact campaign before anything larger.</li>
        </ol>
      </Card>
      <Card title="Addresses for the platform">
        <DescList
          items={[
            ["Tools", <code key="t" className="break-all">{origin + data.toolPath}</code>],
            ["Incoming call lookup", <code key="i" className="break-all">{origin + data.inboundPath}</code>],
            ["Call events", <span key="w" className="block space-y-1">{data.webhookPaths.map((w) => <code key={w} className="block break-all">{origin + w}</code>)}</span>],
            ["Signature", <span key="g" className="text-xs">{data.signatureHeader}: {data.signatureRule}, with {data.timestampHeader} in seconds. Requests older than 5 minutes are refused.</span>],
          ]}
        />
      </Card>
      <Card
        title={`Tools (${data.tools.length})`}
        actions={
          <Button
            size="sm"
            variant="secondary"
            onClick={async () => {
              await navigator.clipboard.writeText(tools);
              setCopied(true);
              setTimeout(() => setCopied(false), 2000);
            }}
          >
            {copied ? "Copied" : "Copy as JSON"}
          </Button>
        }
      >
        <ul className="-my-2 divide-y divide-line">
          {data.tools.map((t) => (
            <li key={t.name} className="py-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <code className="font-medium">{t.name}</code>
                {t.min_verification_level > 0 && <Badge tone="amber">Needs verified caller</Badge>}
              </div>
              <p className="mt-0.5 text-ink-soft">{t.description}</p>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
