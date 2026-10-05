import type { Metadata, Viewport } from "next";
import { Cormorant_Garamond, Geist_Mono, Manrope } from "next/font/google";

import { AuthProvider } from "@/lib/auth";
import { BrandingProvider } from "@/lib/branding";

import "./globals.css";

// A refined serif for display headings and key figures; a clean sans for everything people read and type.
const serif = Cormorant_Garamond({ variable: "--font-serif", subsets: ["latin"], weight: ["500", "600", "700"] });
const body = Manrope({ variable: "--font-body", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "AI Call CRM",
  description: "Contacts and AI-assisted phone calls",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: "#0b1120",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${serif.variable} ${body.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="min-h-full font-sans">
        <BrandingProvider>
          <AuthProvider>{children}</AuthProvider>
        </BrandingProvider>
      </body>
    </html>
  );
}
