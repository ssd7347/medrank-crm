import type { Metadata } from "next";

import { PortalProvider } from "@/lib/portal";

export const metadata: Metadata = {
  title: "Student & parent portal",
  description: "Check your counselling status, deadlines, documents and fees.",
};

export default function PortalLayout({ children }: { children: React.ReactNode }) {
  return <PortalProvider>{children}</PortalProvider>;
}
