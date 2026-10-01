"use client";

import { useState } from "react";

import { Alert, Badge, Button, Card, Checkbox, EmptyState, Field, Input, Loading, Modal, PageHeader, Textarea } from "@/components/ui";
import { ApiError, api, errorMessage } from "@/lib/api";
import { useBranding } from "@/lib/branding";
import type { Faq } from "@/lib/types";
import { useApi } from "@/lib/use-api";

/** What the AI agent may say in answer to general questions. It never answers with anything not written here. */
export default function FaqPage() {
  const { agentName } = useBranding();
  const { data, error, loading, reload } = useApi<Faq[]>("/api/faq");
  const [editing, setEditing] = useState<Faq | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="AI knowledge base"
        subtitle={`The answers ${agentName} may give to general questions on a call: opening hours, prices, policies. Nothing outside this list is ever made up.`}
        actions={<Button onClick={() => setEditing("new")}>Add question</Button>}
      />
      <Card>
        {error && <Alert>{error}</Alert>}
        {loading && !data ? (
          <Loading />
        ) : !data?.length ? (
          <EmptyState title="No questions yet" />
        ) : (
          <ul className="-my-2 divide-y divide-line">
            {data.map((f) => (
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
                <p className="mt-1 text-sm whitespace-pre-wrap text-ink-soft">{f.answer}</p>
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

function FaqForm({ faq, onDone }: { faq?: Faq; onDone: () => void }) {
  const [v, setV] = useState({
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
      <Field label="Question" required error={fieldErrors.question}>
        {(id) => <Input id={id} required maxLength={300} value={v.question} onChange={(e) => setV({ ...v, question: e.target.value })} />}
      </Field>
      <Field label="Answer" required error={fieldErrors.answer} hint="Short, plain and honest: it is read out on the phone.">
        {(id) => <Textarea id={id} required rows={5} maxLength={2000} value={v.answer} onChange={(e) => setV({ ...v, answer: e.target.value })} />}
      </Field>
      <Field label="Words that should bring up this answer" hint="Separate with commas, e.g. timing, open, hours">
        {(id) => <Input id={id} maxLength={500} value={v.keywords} onChange={(e) => setV({ ...v, keywords: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Order" hint="Lower numbers win when two answers match equally">
          {(id) => <Input id={id} type="number" min={0} max={10000} value={v.sortOrder} onChange={(e) => setV({ ...v, sortOrder: e.target.value })} />}
        </Field>
      </div>
      <Checkbox label="In use" checked={v.active} onChange={(e) => setV({ ...v, active: e.target.checked })} />
      <div>
        <Button type="submit" loading={saving}>
          Save
        </Button>
      </div>
    </form>
  );
}
