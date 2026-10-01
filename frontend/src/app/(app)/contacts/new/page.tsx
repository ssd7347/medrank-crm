"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { ContactForm } from "@/components/contact-form";
import { Card, PageHeader } from "@/components/ui";
import { useBranding } from "@/lib/branding";

export default function NewContactPage() {
  const router = useRouter();
  const { contactLabel, plural } = useBranding();
  return (
    <>
      <div className="mb-2 text-sm">
        <Link href="/contacts" className="text-ink-soft hover:text-ink">
          ← {plural}
        </Link>
      </div>
      <PageHeader title={`New ${contactLabel.toLowerCase()}`} />
      <Card>
        <ContactForm onSaved={(c) => router.push(`/contacts/${c.id}`)} onCancel={() => router.push("/contacts")} />
      </Card>
    </>
  );
}
