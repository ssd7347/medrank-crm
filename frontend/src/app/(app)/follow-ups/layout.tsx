"use client";

import { FeatureGate } from "@/components/feature-gate";

export default function FollowUpsLayout({ children }: { children: React.ReactNode }) {
  return <FeatureGate feature="FOLLOW_UPS">{children}</FeatureGate>;
}
