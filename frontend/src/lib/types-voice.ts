// The AI voice calling agent.

import type { ContactStatus, UserRef } from "./types";

export type ScriptKind = "OUTBOUND" | "INBOUND";

export type VoiceCallStatus = "QUEUED" | "DIALING" | "CONNECTED" | "COMPLETED" | "NO_ANSWER" | "FAILED" | "SIMULATED";
export const VOICE_OUTCOMES = [
  "ACKNOWLEDGED",
  "WILL_ACT",
  "INTERESTED",
  "CALLBACK_REQUESTED",
  "HANDED_OFF",
  "NOT_INTERESTED",
  "OPTED_OUT",
  "WRONG_PERSON",
  "NO_INTERACTION",
  "VERIFICATION_FAILED",
] as const;
export type VoiceOutcome = (typeof VOICE_OUTCOMES)[number];
export type HandoffReason = "COMPLAINT_OR_DISPUTE" | "DISTRESS" | "CALLER_REQUEST" | "TOOL_FAILURE" | "VERIFICATION_FAILED" | "OUT_OF_SCOPE";

export type VoiceCallRow = {
  id: string;
  direction: "OUTBOUND" | "INBOUND";
  scriptName: string;
  contactId: number | null;
  personName: string | null;
  phone: string;
  status: VoiceCallStatus;
  outcome: VoiceOutcome | null;
  handoffReason: HandoffReason | null;
  verified: boolean;
  durationSec: number | null;
  testCall: boolean;
  flaggedWrong: boolean;
  provider: string;
  campaignId: number | null;
  summary: string | null;
  createdAt: string;
};

export type TranscriptLine = { role: "agent" | "caller" | "tool"; text: string | null; tool: string | null; status: string | null; ts: string };

export type CallbackItem = {
  id: number;
  contactId: number | null;
  personName: string | null;
  phone: string | null;
  voiceCallId: string | null;
  reason: string;
  preferredTimeText: string | null;
  summary: string | null;
  priority: "NORMAL" | "URGENT";
  status: "OPEN" | "ASSIGNED" | "DONE";
  assignedTo: UserRef | null;
  dueAt: string;
  overdue: boolean;
  createdAt: string;
  doneAt: string | null;
  doneNote: string | null;
};

export type VoiceCallDetail = {
  call: VoiceCallRow;
  transcript: TranscriptLine[];
  tools: { tool: string; status: string; latencyMs: number | null; at: string }[];
  outcomeNote: string | null;
  disconnectReason: string | null;
  /** The call was to be recorded: its opening said it may be. */
  recordingAllowed: boolean;
  /** An outside provider's link to its recording, only for those allowed to listen. */
  recordingRef: string | null;
  /** A recording of this call is kept on the CRM server. */
  recordingHeld: boolean;
  /** This person may play recordings (the admin, or staff the admin allowed). */
  canHearRecordings: boolean;
  flagNote: string | null;
  script: string | null;
  startedAt: string | null;
  endedAt: string | null;
  callbacks: CallbackItem[];
};

export type VoiceOverview = {
  enabled: boolean;
  paused: boolean;
  platform: string;
  live: boolean;
  signingConfigured: boolean;
  dndConnected: boolean;
  transferConfigured: boolean;
  windowStart: string;
  windowEnd: string;
  openCallbacks: number;
  overdueCallbacks: number;
  runningCampaigns: number;
};

export type VoiceMetrics = {
  days: number;
  calls: number;
  connected: number;
  noAnswer: number;
  failed: number;
  simulated: number;
  connectRatePercent: number;
  avgDurationSec: number | null;
  outcomes: Record<string, number>;
  handoffs: Record<string, number>;
  scripts: Record<string, number>;
  handoffRatePercent: number;
  toolCalls: number;
  toolP50Ms: number | null;
  toolP95Ms: number | null;
  toolErrorRatePercent: number;
  optOuts: number;
  flagged: number;
  estimatedCostInr: number;
  costPerCallInr: number | null;
  costThisMonthInr: number;
  monthlyCapInr: number;
};

export type ConsentSource = "WEB_FORM" | "SIGNUP_FORM" | "VERBAL_ON_CALL" | "PAPER_FORM" | "IMPORTED_LIST";
export type PhoneConsent = {
  alternate: boolean;
  label: string;
  phone: string;
  state: "NONE" | "GRANTED" | "REFUSED" | "OPTED_OUT";
  aiCalls: boolean;
  recording: boolean;
  source: ConsentSource | null;
  evidenceRef: string | null;
  capturedAt: string | null;
  revokedAt: string | null;
  revokeNote: string | null;
};

export type CampaignStatus = "DRAFT" | "RUNNING" | "PAUSED" | "DONE";
export type SkipReason =
  | "NO_CONSENT"
  | "DND"
  | "OPTED_OUT"
  | "DO_NOT_CALL"
  | "MAX_ATTEMPTS"
  | "NO_LONGER_NEEDED"
  | "NOT_INTERESTED"
  | "WRONG_PERSON"
  | "OUTSIDE_WINDOW"
  | "NOT_DUE_YET"
  | "CALLED_RECENTLY"
  | "DAILY_LIMIT";

export const FIELD_OPS = ["DUE_WITHIN_DAYS", "OVERDUE", "NOT_EMPTY", "GREATER_THAN", "EQUALS"] as const;
export type FieldOp = (typeof FIELD_OPS)[number];
export const FIELD_OP_LABEL: Record<FieldOp, string> = {
  DUE_WITHIN_DAYS: "is a date within the next … days",
  OVERDUE: "is a date that has passed",
  NOT_EMPTY: "has any value",
  GREATER_THAN: "is a number greater than",
  EQUALS: "equals",
};

export type Campaign = {
  id: number;
  name: string;
  scriptName: string;
  status: CampaignStatus;
  statuses: ContactStatus[];
  tag: string | null;
  importBatchId: number | null;
  fieldKey: string | null;
  fieldOp: FieldOp | null;
  fieldValue: string | null;
  windowStart: string;
  windowEnd: string;
  maxConcurrent: number;
  maxAttempts: number;
  retryGapMin: number;
  branchId: number | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  counts: { total: number; pending: number; done: number; simulated: number; skipped: number; failed: number };
  scriptApproved: boolean;
};

export type CampaignTarget = {
  id: number;
  contactId: number;
  name: string | null;
  phone: string;
  status: "PENDING" | "DONE" | "SKIPPED" | "FAILED" | "SIMULATED";
  skipReason: SkipReason | null;
  attempts: number;
  nextAttemptAt: string | null;
  lastCallId: string | null;
};

export type CampaignDetail = {
  campaign: Campaign;
  targets: CampaignTarget[];
  preview: { checked: number; eligibleNow: number; waiting: Partial<Record<SkipReason, number>>; skipped: Partial<Record<SkipReason, number>>; dndChecked: boolean } | null;
};

export type VoiceScript = {
  id: number;
  name: string;
  kind: ScriptKind;
  version: number;
  model: string;
  systemPrompt: string;
  openingLine: string;
  active: boolean;
  approved: boolean;
  approvedAt: string | null;
  approvedBy: string | null;
  createdAt: string;
  /** What the opening line gives away before the identity check (compliance checklist 2.5). */
  warnings: string[];
};

export type TestCall = {
  callId: string;
  scriptName: string;
  contactName: string | null;
  verified: boolean;
  outcome: VoiceOutcome | null;
  over: boolean;
  transcript: TranscriptLine[];
  added: TranscriptLine[];
  brain: BrainKind;
};

/** Which brain holds the conversation: rules, our own open model, or Claude. */
export type BrainKind = "SIMULATED" | "LOCAL" | "CLAUDE";
export type BrainOption = { kind: BrainKind; label: string; available: boolean; detail: string };
export type AiStatus = {
  speech: {
    available: boolean;
    detail: string;
    sttModel: string | null;
    voice: string | null;
    /** Both speech models loaded and run once; null when the engine did not say. */
    warmed: boolean | null;
    warmUpError: string | null;
  };
  brains: BrainOption[];
  defaultBrain: BrainKind;
};

/** "Ready for a real call?" (GET /api/voice/readiness, admin only): reads and asks, never places a call. */
export type ReadinessState = "OK" | "WARN" | "FAIL";
export type ReadinessCheck = { key: string; title: string; state: ReadinessState; detail: string };
export type Readiness = { ready: boolean; checkedAt: string; checks: ReadinessCheck[] };

export type ProviderSetup = {
  platform: string;
  live: boolean;
  signingConfigured: boolean;
  signatureHeader: string;
  timestampHeader: string;
  signatureRule: string;
  toolPath: string;
  inboundPath: string;
  webhookPaths: string[];
  tools: { name: string; description: string; min_verification_level: number; input_schema: unknown }[];
};

export const SKIP_LABEL: Record<SkipReason, string> = {
  NO_CONSENT: "No consent recorded",
  DND: "On do-not-disturb list",
  DO_NOT_CALL: "Marked do not call",
  OPTED_OUT: "Asked not to be called",
  MAX_ATTEMPTS: "No answer after all attempts",
  NO_LONGER_NEEDED: "No longer needed",
  NOT_INTERESTED: "Not interested",
  WRONG_PERSON: "Wrong number",
  OUTSIDE_WINDOW: "Outside calling hours",
  NOT_DUE_YET: "Retry not due yet",
  CALLED_RECENTLY: "Called recently",
  DAILY_LIMIT: "Daily limit reached",
};
