"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { Alert, Button, Card, EmptyState, Input, Loading, PageHeader, Pagination, Select, Table, Td } from "@/components/ui";
import { formatDateTime, label } from "@/lib/format";
import type { Page, User } from "@/lib/types";
import { useApi } from "@/lib/use-api";

type AuditEntry = {
  id: number;
  at: string;
  action: string;
  byId: number | null;
  by: string | null;
  entityType: string;
  entityId: number | null;
  details: string | null;
  link: string | null;
};

/** What each recorded action means, in words. Anything not listed is shown from its code. */
const ACTIONS: Record<string, string> = {
  LOGIN: "Signed in",
  LOGIN_FAILED: "Failed sign-in",
  PASSWORD_CHANGED: "Changed their password",
  PASSWORD_RESET: "Reset a password",
  USER_CREATED: "Added a staff user",
  USER_UPDATED: "Changed a staff user",
  CONTACT_CREATED: "Added a contact",
  CONTACT_UPDATED: "Edited a contact",
  CONTACT_STATUS: "Changed a contact's stage",
  CONTACT_ASSIGNED: "Assigned a contact",
  CONTACT_TAGGED: "Changed a contact's tags",
  CONTACTS_MERGED: "Merged duplicate contacts",
  DUPLICATES_DISMISSED: "Marked two contacts as different people",
  CONTACTS_IMPORTED: "Imported contacts",
  CONTACT_DATA_EXPORTED: "Downloaded a person's data",
  CONTACT_ERASED: "Erased a person",
  VOICE_CONSENT_RECORDED: "Recorded consent to AI calls",
  FIELD_CREATED: "Added a custom field",
  FIELD_UPDATED: "Changed a custom field",
  STAGE_CREATED: "Added a stage",
  STAGE_UPDATED: "Changed a stage",
  STAGE_DELETED: "Deleted a stage",
  STAGES_REORDERED: "Reordered the stages",
  BRANCH_CREATED: "Added a branch",
  BRANCH_UPDATED: "Changed a branch",
  SETTINGS_UPDATED: "Changed company settings",
  LOGO_UPDATED: "Changed the logo",
  LOGO_REMOVED: "Removed the logo",
  STARTER_PACK_APPLIED: "Loaded an industry starter pack",
  FAQ_CREATED: "Added an AI knowledge-base answer",
  FAQ_UPDATED: "Changed an AI knowledge-base answer",
  VOICE_SCRIPT_CREATED: "Saved a call script version",
  VOICE_SCRIPT_APPROVED: "Approved a call script",
  VOICE_CAMPAIGN_CREATED: "Created a campaign",
  VOICE_CAMPAIGN_UPDATED: "Changed a campaign",
  VOICE_CAMPAIGN_STARTED: "Started a campaign",
  VOICE_CAMPAIGN_PAUSED: "Paused a campaign",
  VOICE_CAMPAIGN_FINISHED: "Finished a campaign",
  VOICE_PAUSED_ALL: "Paused all AI calls",
  VOICE_RESUMED_ALL: "Resumed AI calls",
  VOICE_CALL_VIEWED: "Opened an AI call",
  VOICE_CALL_FLAGGED: "Flagged an AI call as wrong",
  VOICE_CALLBACK_ASSIGNED: "Assigned a call-back",
  VOICE_CALLBACK_DONE: "Closed a call-back",
};

function actionLabel(action: string) {
  if (ACTIONS[action]) return ACTIONS[action];
  if (action.startsWith("CONTACTS_BULK_")) return `Changed many contacts at once (${label(action.slice("CONTACTS_BULK_".length)).toLowerCase()})`;
  return label(action);
}

/** A failed sign-in is recorded under the account that was tried, which is not necessarily who tried it. */
function who(e: AuditEntry) {
  if (e.action === "LOGIN_FAILED") return e.by ? `Someone, on ${e.by}'s account` : "Someone, unknown account";
  return e.by ?? "The CRM itself";
}

/** Sign-ins record "otp" for a one-time code; a password sign-in (the first admin's fallback) records nothing. */
function details(e: AuditEntry) {
  if ((e.action === "LOGIN" || e.action === "LOGIN_FAILED") && e.details === "otp") return "With a one-time code";
  return e.details ?? "—";
}

export default function AuditPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Audit />
    </Suspense>
  );
}

function Audit() {
  const params = useSearchParams();
  const contact = params.get("contact") ?? "";
  const [action, setAction] = useState("");
  const [by, setBy] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const actions = useApi<string[]>("/api/audit/actions");
  const staff = useApi<User[]>("/api/users");
  const { data, error, loading } = useApi<Page<AuditEntry>>("/api/audit", { action, by, contact, from, to, page, size: 50 });

  const change = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement | HTMLInputElement>) => {
    setter(e.target.value);
    setPage(0);
  };
  const filtered = action || by || from || to;

  return (
    <>
      <PageHeader
        title="Audit log"
        subtitle="Who did what, and when: sign-ins, changes to contacts and settings, consent, scripts, campaigns, downloads and erasures. Entries cannot be changed or removed."
      />
      <div className="space-y-4">
        {contact && (
          <Alert tone="blue">
            Showing one contact&rsquo;s history only. <Link href="/admin/audit" className="underline">Show everything</Link>
          </Alert>
        )}
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-4">
          <Select
            aria-label="What"
            value={action}
            onChange={change(setAction)}
            placeholder="Every action"
            options={(actions.data ?? []).map((a) => ({ value: a, label: actionLabel(a) }))}
          />
          <Select
            aria-label="Who"
            value={by}
            onChange={change(setBy)}
            placeholder="Everyone"
            options={(staff.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))}
          />
          <Input type="date" aria-label="From" value={from} onChange={change(setFrom)} />
          <Input type="date" aria-label="To" value={to} onChange={change(setTo)} />
        </div>
        {filtered && (
          <div>
            <Button variant="ghost" size="sm" onClick={() => (setAction(""), setBy(""), setFrom(""), setTo(""), setPage(0))}>
              Clear filters
            </Button>
          </div>
        )}
        {(error || actions.error) && <Alert>{error ?? actions.error}</Alert>}
        <Card>
          <div className="-m-4">
            {loading && !data ? (
              <Loading />
            ) : !data?.items.length ? (
              <EmptyState title="Nothing recorded matches">Try fewer filters, or other dates (Indian time).</EmptyState>
            ) : (
              <>
                <Table head={["When", "Who", "What", "Record", "Details"]}>
                  {data.items.map((e) => (
                    <tr key={e.id}>
                      <Td className="whitespace-nowrap">{formatDateTime(e.at)}</Td>
                      <Td>{who(e)}</Td>
                      <Td>{actionLabel(e.action)}</Td>
                      <Td className="whitespace-nowrap">
                        {e.link ? (
                          <Link href={e.link} className="text-brand-700 hover:underline">
                            {label(e.entityType)}
                            {e.entityId ? ` #${e.entityId}` : ""}
                          </Link>
                        ) : (
                          <span className="text-ink-soft">
                            {label(e.entityType)}
                            {e.entityId ? ` #${e.entityId}` : ""}
                          </span>
                        )}
                      </Td>
                      <Td className="max-w-md text-xs break-words text-ink-soft">{details(e)}</Td>
                    </tr>
                  ))}
                </Table>
                <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
              </>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}
