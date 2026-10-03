// Mirrors the backend DTOs. Keep enum lists in the same order as the Java enums.

export const ROLES = ["SUPER_ADMIN", "MANAGER", "AGENT"] as const;
export type Role = (typeof ROLES)[number];

/** A stage key. Stages are named by each customer (see /api/stages); these five always exist. */
export type ContactStatus = string;
export const BUILT_IN_STAGES = ["NEW", "CONTACTED", "INTERESTED", "NOT_INTERESTED", "DO_NOT_CALL"] as const;

export type StageCategory = "OPEN" | "WON" | "LOST";
export type Stage = { id: number; key: string; label: string; color: string; category: StageCategory; sortOrder: number; builtIn: boolean };

export const FEATURES = ["AI_CALLS", "IMPORT", "EXPORT", "FOLLOW_UPS"] as const;
export type Feature = (typeof FEATURES)[number];

export type StarterPack = { id: string; name: string; description: string; contactLabel: string; stages: string[]; fields: string[]; scripts: string[]; faqs: number };
export type PackApplied = { id: string; stagesAdded: number; fieldsAdded: number; scriptsAdded: number; faqsAdded: number };

export const MANUAL_ACTIVITY_TYPES = ["NOTE", "CALL", "WHATSAPP", "EMAIL", "MEETING"] as const;
export type ActivityType = (typeof MANUAL_ACTIVITY_TYPES)[number] | "STATUS_CHANGE" | "ASSIGNMENT" | "IMPORT";

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type UserRef = { id: number; fullName: string; role: Role };
export type BranchRef = { id: number; name: string; code: string };
export type Branch = BranchRef & { city: string | null; state: string | null; active: boolean };

export type User = {
  id: number;
  fullName: string;
  email: string;
  phone: string | null;
  role: Role;
  active: boolean;
  branch: BranchRef | null;
  createdAt: string;
};

export type AuthResponse = { accessToken: string; expiresIn: number; user: User };

// ------------------------------------------------------------------ contacts and custom fields

export const FIELD_TYPES = ["TEXT", "NUMBER", "AMOUNT", "DATE"] as const;
export type FieldType = (typeof FIELD_TYPES)[number];
export const AGENT_ACCESS = ["NONE", "AFTER_VERIFY", "ANYONE"] as const;
export type AgentAccess = (typeof AGENT_ACCESS)[number];

export const AGENT_ACCESS_LABEL: Record<AgentAccess, string> = {
  NONE: "Never read out",
  AFTER_VERIFY: "Only after the caller is verified",
  ANYONE: "To anyone who answers",
};

export type FieldDef = {
  id: number;
  key: string;
  label: string;
  fieldType: FieldType;
  agentAccess: AgentAccess;
  verifyWith: boolean;
  sortOrder: number;
  active: boolean;
};

export type ContactListItem = {
  id: number;
  fullName: string;
  phone: string;
  email: string | null;
  status: ContactStatus;
  source: string | null;
  assignedTo: UserRef | null;
  branch: BranchRef | null;
  fields: Record<string, string>;
  createdAt: string;
};

export type Contact = {
  id: number;
  fullName: string;
  phone: string;
  altPhone: string | null;
  email: string | null;
  status: ContactStatus;
  source: string | null;
  notes: string | null;
  assignedTo: UserRef | null;
  branch: BranchRef | null;
  importBatchId: number | null;
  fields: Record<string, string>;
  createdAt: string;
  updatedAt: string;
};

export type ContactRequest = {
  fullName: string;
  phone: string;
  altPhone: string | null;
  email: string | null;
  status: ContactStatus | null;
  source: string | null;
  notes: string | null;
  assignedToId: number | null;
  branchId: number | null;
  fields: Record<string, string>;
};

export type Activity = {
  id: number;
  type: ActivityType;
  outcome: string | null;
  notes: string | null;
  createdBy: UserRef | null;
  createdAt: string;
};

export type FollowUp = {
  id: number;
  contactId: number;
  contactName: string;
  assignedTo: UserRef;
  dueAt: string;
  purpose: string;
  completedAt: string | null;
};

export const CALL_OUTCOMES = ["CONNECTED", "NO_ANSWER", "BUSY", "SWITCHED_OFF", "CALL_BACK_LATER", "WRONG_NUMBER"] as const;
export type CallOutcome = (typeof CALL_OUTCOMES)[number];

export type Call = {
  id: number;
  phone: string;
  direction: "OUTBOUND" | "INBOUND";
  outcome: CallOutcome;
  durationSeconds: number | null;
  notes: string | null;
  provider: string | null;
  recordingUrl: string | null;
  calledBy: UserRef | null;
  calledAt: string;
  /** Set for calls made by the AI agent: opens its transcript. */
  voiceCallId: string | null;
};

// ------------------------------------------------------------------ imports

export type ImportPreview = {
  fileName: string;
  headers: string[];
  sample: string[][];
  totalRows: number;
  suggested: Record<string, string>;
};

export type ImportResult = {
  batchId: number;
  totalRows: number;
  created: number;
  updated: number;
  skipped: number;
  problems: string[];
  newFields: string[];
};

export type ImportBatch = { id: number; fileName: string; totalRows: number; created: number; updated: number; skipped: number; createdAt: string };

// ------------------------------------------------------------------ everything else

export type Branding = {
  companyName: string;
  agentName: string;
  brandColor: string;
  supportPhone: string | null;
  contactLabel: string;
  loginHeadline: string | null;
  loginText: string | null;
  logoUrl: string | null;
  industryPack: string | null;
  features: Feature[];
};

export type AppNotification = {
  id: number;
  contactId: number | null;
  type: string;
  priority: "NORMAL" | "URGENT";
  title: string;
  body: string | null;
  link: string | null;
  createdAt: string;
  readAt: string | null;
};

export type Faq = { id: number; question: string; answer: string; keywords: string | null; sortOrder: number; active: boolean };

export type Integration = { key: string; name: string; module: string; state: "CONNECTED" | "NOT_CONNECTED"; today: string; needs: string };

export type DashboardSummary = {
  contactsByStatus: Record<string, number>;
  totalContacts: number;
  newThisWeek: number;
  myFollowUpsDue: number;
  openCallbacks: number;
  aiCallsToday: number;
  aiCallsSimulatedToday: number;
};
