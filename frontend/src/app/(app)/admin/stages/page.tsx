"use client";

import { useState } from "react";

import { ContactStatusBadge } from "@/components/badges";
import { Alert, Badge, Button, Card, Field, Input, Modal, PageHeader, Select } from "@/components/ui";
import { api, errorMessage } from "@/lib/api";
import { useBranding } from "@/lib/branding";
import { useStages } from "@/lib/stages";
import type { Stage, StageCategory } from "@/lib/types";

const CATEGORY_LABEL: Record<StageCategory, string> = { OPEN: "Still in progress", WON: "Won (a success)", LOST: "Lost / closed" };

/** The steps a contact moves through, named by this customer ("New → Visited → Booked"). */
export default function StagesPage() {
  const { stages, reload } = useStages();
  const { plural, has } = useBranding();
  const [editing, setEditing] = useState<Partial<Stage> | null>(null);
  const [deleting, setDeleting] = useState<Stage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function move(index: number, by: -1 | 1) {
    const ids = stages.map((s) => s.id);
    const j = index + by;
    [ids[index], ids[j]] = [ids[j], ids[index]];
    setBusy(true);
    setError(null);
    try {
      await api("/api/stages/order", { method: "PUT", body: { ids } });
      reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Stages"
        subtitle={`The steps your ${plural.toLowerCase()} move through, in your own words.`}
        actions={<Button onClick={() => setEditing({ color: "#7c3aed", category: "OPEN" })}>New stage</Button>}
      />
      {has("AI_CALLS") && (
        <div className="mb-4">
          <Alert tone="blue">
            Stages marked <strong>Built in</strong> are used by the AI agent: it moves people to them after a call (for example to &ldquo;Interested&rdquo;, or to
            &ldquo;Do not call&rdquo; when someone asks not to be called). Rename them freely; they cannot be deleted.
          </Alert>
        </div>
      )}
      {error && <Alert>{error}</Alert>}
      <Card>
        <ol className="-m-4 divide-y divide-line">
          {stages.map((s, i) => (
            <li key={s.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="flex flex-col">
                <button
                  className="rounded px-1 text-ink-faint hover:bg-muted hover:text-ink disabled:opacity-30"
                  aria-label={`Move ${s.label} up`}
                  disabled={busy || i === 0}
                  onClick={() => move(i, -1)}
                >
                  ▲
                </button>
                <button
                  className="rounded px-1 text-ink-faint hover:bg-muted hover:text-ink disabled:opacity-30"
                  aria-label={`Move ${s.label} down`}
                  disabled={busy || i === stages.length - 1}
                  onClick={() => move(i, 1)}
                >
                  ▼
                </button>
              </div>
              <div className="min-w-0 flex-1">
                <ContactStatusBadge status={s.key} />
                <p className="mt-1 text-xs text-ink-faint">
                  {CATEGORY_LABEL[s.category]}
                  {s.builtIn && (
                    <span className="ml-2">
                      <Badge>Built in</Badge>
                    </span>
                  )}
                </p>
              </div>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" onClick={() => setEditing(s)}>
                  Edit
                </Button>
                {!s.builtIn && (
                  <Button size="sm" variant="secondary" onClick={() => setDeleting(s)}>
                    Delete
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ol>
      </Card>
      {editing && <StageForm stage={editing} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), reload())} />}
      {deleting && <DeleteStage stage={deleting} stages={stages} onClose={() => setDeleting(null)} onDone={() => (setDeleting(null), reload())} />}
    </>
  );
}

function StageForm({ stage, onClose, onSaved }: { stage: Partial<Stage>; onClose: () => void; onSaved: () => void }) {
  const [label, setLabel] = useState(stage.label ?? "");
  const [color, setColor] = useState(stage.color ?? "#7c3aed");
  const [category, setCategory] = useState<StageCategory>(stage.category ?? "OPEN");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(stage.id ? `/api/stages/${stage.id}` : "/api/stages", { method: stage.id ? "PUT" : "POST", body: { label, color, category } });
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={stage.id ? `Edit "${stage.label}"` : "New stage"}>
      <form onSubmit={save} className="space-y-4">
        {error && <Alert>{error}</Alert>}
        <Field label="Name" required hint="For example Visited, Booked, Paid.">
          {(id) => <Input id={id} required maxLength={40} value={label} onChange={(e) => setLabel(e.target.value)} autoFocus />}
        </Field>
        <Field label="Colour">
          {(id) => (
            <div className="flex items-center gap-3">
              <input id={id} type="color" value={color} onChange={(e) => setColor(e.target.value)} className="h-10 w-14 cursor-pointer rounded-lg border border-line bg-surface p-1" />
              <Input aria-label="Colour code" maxLength={7} value={color} onChange={(e) => setColor(e.target.value)} className="font-mono" />
            </div>
          )}
        </Field>
        <Field label="Counts as" hint={stage.builtIn ? "Fixed for built-in stages." : "Used by reports to tell successes from losses."}>
          {(id) => (
            <Select
              id={id}
              disabled={stage.builtIn}
              value={category}
              options={(Object.keys(CATEGORY_LABEL) as StageCategory[]).map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))}
              onChange={(e) => setCategory(e.target.value as StageCategory)}
            />
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={saving}>
            Save
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function DeleteStage({ stage, stages, onClose, onDone }: { stage: Stage; stages: Stage[]; onClose: () => void; onDone: () => void }) {
  const others = stages.filter((s) => s.id !== stage.id);
  const [moveTo, setMoveTo] = useState(others[0]?.key ?? "");
  const [needsMove, setNeedsMove] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/stages/${stage.id}`, { method: "DELETE", query: needsMove ? { moveTo } : undefined });
      onDone();
    } catch (e) {
      // The server says when contacts are still at this stage; then ask where they should go.
      if (!needsMove && errorMessage(e).includes("Choose a stage")) setNeedsMove(true);
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={`Delete "${stage.label}"?`}>
      <div className="space-y-4">
        {error && <Alert tone={needsMove ? "amber" : "red"}>{error}</Alert>}
        {needsMove ? (
          <Field label="Move them to">
            {(id) => <Select id={id} value={moveTo} options={others.map((s) => ({ value: s.key, label: s.label }))} onChange={(e) => setMoveTo(e.target.value)} />}
          </Field>
        ) : (
          <p className="text-sm text-ink-soft">The stage is removed for everyone. Its history stays on each contact&rsquo;s timeline.</p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" loading={busy} onClick={remove}>
            {needsMove ? "Move and delete" : "Delete"}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
