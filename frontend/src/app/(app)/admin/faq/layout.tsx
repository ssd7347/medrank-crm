"use client";

import { FeatureGate } from "@/components/feature-gate";

export default function FaqLayout({ children }: { children: React.ReactNode }) {
  return <FeatureGate feature="AI_CALLS">{children}</FeatureGate>;
}
