"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Select, Textarea, cx } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { label } from "@/lib/format";
import { LANGUAGES, type Language } from "@/lib/types";
import type { Faq } from "@/lib/types-advanced";
import { useApi } from "@/lib/use-api";

export default function FaqPage() {
  const { data, error, loading, reload } = useApi<Faq[]>("/api/faq");
  const [language, setLanguage] = useState<Language>("ENGLISH");
  const [editing, setEditing] = useState<Faq | "new" | null>(null);
  const rows = (data ?? []).filter((f) => f.language === language);

  return (
    <>
      <PageHeader
        title="Assistant knowledge base"
        subtitle="The questions and answers the website assistant uses. It only ever answers with what is written here."
        actions={
          <>
            <Link href="/ask" target="_blank" className="rounded-lg border border-line bg-surface px-3.5 py-2 text-sm font-medium hover:bg-muted">
              Open the assistant
            </Link>
            <Button onClick={() => setEditing("new")}>Add question</Button>
          </>
        }
      />
      <div className="mb-4 flex gap-1.5">
        {LANGUAGES.map((l) => (
          <button
            key={l}
            onClick={() => setLanguage(l)}
            aria-pressed={language === l}
            className={cx("rounded-full border px-3 py-1 text-sm", language === l ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface hover:bg-muted")}
          >
            {label(l)} ({(data ?? []).filter((f) => f.language === l).length})
          </button>
        ))}
      </div>
      {language !== "ENGLISH" && (
        <div className="mb-4">
          <Alert tone="amber">The starter {label(language)} answers were machine-written. Please have a native speaker read them before you share the assistant link.</Alert>
        </div>
      )}
      <Card>
        {error && <Alert>{error}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !rows.length ? (
          <EmptyState title={`No ${label(language)} questions yet`} />
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {rows.map((f) => (
              <li key={f.id} className="py-3">
                <div className="flex items-start justify-between gap-3">
                  <p className="text-sm font-medium">
                    {f.question}
                    {!f.active && (
                      <span className="ml-2 align-middle">
                        <Badge>Hidden</Badge>
                      </span>
                    )}
                  </p>
                  <Button size="sm" variant="ghost" onClick={() => setEditing(f)} className="shrink-0">
                    Edit
                  </Button>
                </div>
                <p className="mt-1 text-sm text-ink-soft whitespace-pre-wrap">{f.answer}</p>
                {f.keywords && <p className="mt-1 text-xs text-ink-faint">Matches: {f.keywords}</p>}
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "Add question" : "Edit question"} wide>
        {editing !== null && (
          <FaqForm
            faq={editing === "new" ? undefined : editing}
            language={language}
            onDone={() => {
              setEditing(null);
              reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function FaqForm({ faq, language, onDone }: { faq?: Faq; language: Language; onDone: () => void }) {
  const [v, setV] = useState({
    language: faq?.language ?? language,
    question: faq?.question ?? "",
    answer: faq?.answer ?? "",
    keywords: faq?.keywords ?? "",
    sortOrder: String(faq?.sortOrder ?? 100),
    active: faq?.active ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      const body = { ...v, sortOrder: Number(v.sortOrder) };
      if (faq) await api(`/api/faq/${faq.id}`, { method: "PUT", body });
      else await api("/api/faq", { body });
      onDone();
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setFieldErrors(err.body.errors);
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4">
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Language">{(id) => <Select id={id} value={v.language} onChange={(e) => setV({ ...v, language: e.target.value as Language })} options={LANGUAGES} labelFor={label} />}</Field>
        <Field label="Order" hint="Lower numbers are suggested first">
          {(id) => <Input id={id} type="number" min={0} max={10000} value={v.sortOrder} onChange={(e) => setV({ ...v, sortOrder: e.target.value })} />}
        </Field>
      </div>
      <Field label="Question" required error={fieldErrors.question}>
        {(id) => <Input id={id} required maxLength={300} value={v.question} onChange={(e) => setV({ ...v, question: e.target.value })} />}
      </Field>
      <Field label="Answer" required error={fieldErrors.answer} hint="Plain and honest. Never promise a seat.">
        {(id) => <Textarea id={id} required rows={5} maxLength={2000} value={v.answer} onChange={(e) => setV({ ...v, answer: e.target.value })} />}
      </Field>
      <Field label="Words that should bring up this answer" hint="Separate with commas, e.g. refund, money back, cancel">
        {(id) => <Input id={id} maxLength={500} value={v.keywords} onChange={(e) => setV({ ...v, keywords: e.target.value })} />}
      </Field>
      <Checkbox label="Shown to visitors" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
