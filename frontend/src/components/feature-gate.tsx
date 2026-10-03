"use client";

import { EmptyState } from "@/components/ui";
import { useBranding } from "@/lib/branding";
import type { Feature } from "@/lib/types";

/** For pages opened by address: a module this installation has not bought shows a short note instead. */
export function FeatureGate({ feature, children }: { feature: Feature; children: React.ReactNode }) {
  const { has } = useBranding();
  if (!has(feature)) {
    return <EmptyState title="Not part of your plan">Ask your CRM provider to switch this on.</EmptyState>;
  }
  return <>{children}</>;
}
