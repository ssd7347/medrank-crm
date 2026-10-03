"use client";

import { FeatureGate } from "@/components/feature-gate";
import { PageHeader } from "@/components/ui";
import { SimulatedBanner, VoiceTabs } from "@/components/voice";

export default function VoiceLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <PageHeader title="AI voice agent" subtitle="Reminder and status calls answered by an AI assistant, with a person taking over whenever needed." />
      <FeatureGate feature="AI_CALLS">
        <VoiceTabs />
        <SimulatedBanner />
        {children}
      </FeatureGate>
    </>
  );
}
