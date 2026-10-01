// Phase 6: AI voice calling agent (spec 4.29 and section 18).

import type { Language, UserRef } from "./types";

export const VOICE_PURPOSES = [
  "DEADLINE_REMINDER",
  "DOC_NUDGE",
  "FEE_REMINDER",
  "LEAD_QUALIFY",
  "MISSED_CALL_FOLLOWUP",
  "INBOUND_STATUS",
  "INBOUND_FAQ",
] as const;
export type VoicePurpose = (typeof VOICE_PURPOSES)[number];
export const OUTBOUND_PURPOSES: VoicePurpose[] = ["DEADLINE_REMINDER", "DOC_NUDGE", "FEE_REMINDER", "LEAD_QUALIFY", "MISSED_CALL_FOLLOWUP"];

export const PURPOSE_LABEL: Record<VoicePurpose, string> = {
  DEADLINE_REMINDER: "Deadline reminder",
  DOC_NUDGE: "Missing documents",
  FEE_REMINDER: "Fee reminder",
  LEAD_QUALIFY: "New enquiry (first call)",
  MISSED_CALL_FOLLOWUP: "Missed-call follow-up",
  INBOUND_STATUS: "Incoming: status question",
  INBOUND_FAQ: "Incoming: general question",
};

export const PURPOSE_HELP: Record<VoicePurpose, string> = {
  DEADLINE_REMINDER: "Students with a choice-filling, result, reporting or seat-decision date in the next few days.",
  DOC_NUDGE: "Students who still have required documents missing.",
  FEE_REMINDER: "Students with a consultancy-fee instalment due soon or overdue.",
  LEAD_QUALIFY: "Leads still marked New.",
  MISSED_CALL_FOLLOWUP: "Students and leads whose latest call from staff was not answered.",
  INBOUND_STATUS: "",
  INBOUND_FAQ: "",
};

export type VoiceCallStatus = "QUEUED" | "DIALING" | "CONNECTED" | "COMPLETED" | "NO_ANSWER" | "FAILED" | "SIMULATED";
export const VOICE_OUTCOMES = [
  "ACKNOWLEDGED",
  "WILL_ACT",
  "CALLBACK_REQUESTED",
  "HANDED_OFF",
  "NOT_INTERESTED",
  "OPTED_OUT",
  "WRONG_PERSON",
  "NO_INTERACTION",
  "VERIFICATION_FAILED",
] as const;
export type VoiceOutcome = (typeof VOICE_OUTCOMES)[number];
export type HandoffReason =
  | "DECISION_ADVICE"
  | "REFUND_OR_DISPUTE"
  | "DISTRESS"
  | "CALLER_REQUEST"
  | "TOOL_FAILURE"
  | "VERIFICATION_FAILED"
  | "OUT_OF_SCOPE";

export type VoiceCallRow = {
  id: string;
  direction: "OUTBOUND" | "INBOUND";
  purpose: VoicePurpose;
  studentId: number | null;
  leadId: number | null;
  personName: string | null;
  phone: string;
  language: Language;
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
  studentId: number | null;
  leadId: number | null;
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
  recordingAllowed: boolean;
  recordingRef: string | null;
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
  purposes: Record<string, number>;
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

export type ConsentSource = "WEB_FORM" | "ONBOARDING" | "VERBAL_ON_CALL" | "PAPER_FORM";
export type PhoneConsent = {
  personType: "LEAD" | "STUDENT" | "PARENT";
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
  | "MAX_ATTEMPTS"
  | "NO_LONGER_NEEDED"
  | "NOT_INTERESTED"
  | "WRONG_PERSON"
  | "OUTSIDE_WINDOW"
  | "NOT_DUE_YET"
  | "CALLED_RECENTLY"
  | "DAILY_LIMIT";

export type Campaign = {
  id: number;
  name: string;
  purpose: VoicePurpose;
  status: CampaignStatus;
  windowStart: string;
  windowEnd: string;
  maxConcurrent: number;
  maxAttempts: number;
  retryGapMin: number;
  lookaheadDays: number;
  branchId: number | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  counts: { total: number; pending: number; done: number; simulated: number; skipped: number; failed: number };
  scriptApproved: boolean;
};

export type CampaignTarget = {
  id: number;
  studentId: number | null;
  leadId: number | null;
  name: string | null;
  phone: string;
  recipient: "LEAD" | "STUDENT" | "PARENT";
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
  purpose: VoicePurpose;
  language: Language;
  version: number;
  model: string;
  systemPrompt: string;
  openingLine: string;
  active: boolean;
  approved: boolean;
  approvedAt: string | null;
  approvedBy: string | null;
  createdAt: string;
};

export type TestCall = {
  callId: string;
  purpose: VoicePurpose;
  personName: string | null;
  verified: boolean;
  outcome: VoiceOutcome | null;
  over: boolean;
  transcript: TranscriptLine[];
  added: TranscriptLine[];
};

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
