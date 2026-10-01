"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, DescList, Loading } from "@/components/ui";
import type { ProviderSetup } from "@/lib/types-voice";
import { useApi } from "@/lib/use-api";

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
      <Card title="What is connected">
        <DescList
          items={[
            ["Voice platform", <span key="p" className="flex items-center gap-2">{data.platform} <Badge tone={data.live ? "green" : "amber"}>{data.live ? "Live" : "Simulated"}</Badge></span>],
            ["Request signing secret", <Badge key="s" tone={data.signingConfigured ? "green" : "red"}>{data.signingConfigured ? "Set" : "Not set: platform requests are refused"}</Badge>],
          ]}
        />
      </Card>
      <Card title="To connect a provider">
        <ol className="list-decimal space-y-2 pl-5 text-sm text-ink-soft">
          <li>Choose a voice-AI platform (Retell, Vapi, Bland or Bolna) and an Indian telephony provider (Exotel or Knowlarity), and complete DLT registration.</li>
          <li>
            A developer adds one small adapter class for that platform (see <code>docs/voice-agent.md</code> in the code) and sets <code>VOICE_PLATFORM</code>,{" "}
            <code>VOICE_HMAC_SECRET</code> and <code>VOICE_DESK_NUMBER</code> on the server.
          </li>
          <li>In the platform, register the tools below and point the webhooks at the addresses below. The CRM must be reachable on a public HTTPS address.</li>
          <li>Approve the scripts, record consent, then try the test console and a small campaign before anything larger.</li>
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
