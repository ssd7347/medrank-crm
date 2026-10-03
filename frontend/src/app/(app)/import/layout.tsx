"use client";

import { FeatureGate } from "@/components/feature-gate";

export default function ImportLayout({ children }: { children: React.ReactNode }) {
  return <FeatureGate feature="IMPORT">{children}</FeatureGate>;
}
