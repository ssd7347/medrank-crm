"use client";

import { usePathname } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useState } from "react";

import { FEATURES, type Branding, type Feature } from "./types";

// The customer's company name, logo, AI agent name, brand colour and the word for "contact" come from the
// server (Settings → Company), so one build of the CRM can be sold to many companies unchanged. So does the
// list of modules this installation has bought (`has("AI_CALLS")`), which hides the others.

const DEFAULT: Branding = {
  companyName: "AI Call CRM",
  agentName: "Asha",
  brandColor: "#c9a96e",
  supportPhone: null,
  contactLabel: "Contact",
  loginHeadline: null,
  loginText: null,
  logoUrl: null,
  industryPack: null,
  features: [...FEATURES],
};

type BrandingState = Branding & { reload: () => void; plural: string; has: (f: Feature) => boolean };

const BrandingContext = createContext<BrandingState>({ ...DEFAULT, reload: () => undefined, plural: "Contacts", has: () => true });

/** "Customer" -> "Customers", "Patient" -> "Patients". */
export function pluralOf(word: string) {
  if (/(s|x|ch|sh)$/i.test(word)) return word + "es";
  if (/[^aeiou]y$/i.test(word)) return word.slice(0, -1) + "ies";
  return word + "s";
}

/** Dark text on a light brand colour (gold, sand), ivory text on a deep one (navy, teal). */
function onColour(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const v = parseInt(hex.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.3 ? "#17130c" : "#fffaf0";
}

export function BrandingProvider({ children }: { children: React.ReactNode }) {
  const [branding, setBranding] = useState<Branding>(DEFAULT);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    fetch("/api/public/branding")
      .then((r) => (r.ok ? r.json() : null))
      .then((b: Branding | null) => {
        if (b) setBranding(b);
      })
      .catch(() => undefined);
  }, [version]);

  // Next sets the tab title on each page change; put the company name back afterwards.
  const pathname = usePathname();
  useEffect(() => {
    document.title = branding.companyName;
  }, [branding.companyName, pathname]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const c = /^#[0-9a-f]{6}$/i.test(branding.brandColor) ? branding.brandColor : DEFAULT.brandColor;
  // Shades of the brand colour, so every bg-brand-* / text-brand-* class follows the customer's colour:
  // 50–300 pale tints for backgrounds, 700–900 deep tones readable as text (see globals.css).
  const css = `:root{--color-brand-50:color-mix(in srgb,${c} 9%,white);--color-brand-100:color-mix(in srgb,${c} 16%,white);--color-brand-200:color-mix(in srgb,${c} 30%,white);--color-brand-300:color-mix(in srgb,${c} 50%,white);--color-brand-400:color-mix(in srgb,${c} 75%,white);--color-brand-500:color-mix(in srgb,${c} 88%,white);--color-brand-600:${c};--color-brand-700:color-mix(in srgb,${c} 62%,black);--color-brand-800:color-mix(in srgb,${c} 50%,black);--color-brand-900:color-mix(in srgb,${c} 40%,black);--color-on-brand:${onColour(c)};}`;

  return (
    <BrandingContext.Provider value={{ ...branding, reload, plural: pluralOf(branding.contactLabel), has: (f) => branding.features.includes(f) }}>
      <style>{css}</style>
      {children}
    </BrandingContext.Provider>
  );
}

export function useBranding() {
  return useContext(BrandingContext);
}
