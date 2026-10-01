// Phase 5 DTOs: loans, sessions, calls, scoring, agreements, FAQ knowledge base, integrations.

import type { Language, LeadSource, LeadStatus, UserRef } from "./types";

export const LOAN_STATUSES = ["DRAFT", "SUBMITTED", "DOCS_PENDING", "SANCTIONED", "DISBURSED", "REJECTED", "WITHDRAWN"] as const;
export type LoanStatus = (typeof LOAN_STATUSES)[number];

export const LOAN_STATUS_LABEL: Record<LoanStatus, string> = {
  DRAFT: "Preparing",
  SUBMITTED: "Submitted to lender",
  DOCS_PENDING: "Lender needs documents",
  SANCTIONED: "Sanctioned",
  DISBURSED: "Disbursed",
  REJECTED: "Rejected",
  WITHDRAWN: "Withdrawn",
};

export type LoanPartner = {
  id: number;
  name: string;
  interestInfo: string | null;
  maxAmount: number | null;
  eligibility: string | null;
  contactName: string | null;
  contactPhone: string | null;
  active: boolean;
};

export type Loan = {
  id: number;
  studentId: number;
  studentName: string;
  studentPhone: string;
  counsellor: UserRef | null;
  partnerId: number;
  partnerName: string;
  amountRequested: number;
  amountSanctioned: number | null;
  status: LoanStatus;
  neededBy: string | null;
  appliedOn: string | null;
  decidedOn: string | null;
  referenceNo: string | null;
  coApplicant: string | null;
  notes: string | null;
  handledBy: UserRef | null;
  risk: "NONE" | "AT_RISK" | "LATE";
  daysLeft: number | null;
  updatedAt: string;
};

export type StudentLoans = { applications: Loan[]; suggestedNeededBy: string | null };

export const SESSION_MODES = ["VIDEO", "PHONE", "IN_PERSON"] as const;
export type SessionMode = (typeof SESSION_MODES)[number];
export const SESSION_STATUSES = ["SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW"] as const;
export type SessionStatus = (typeof SESSION_STATUSES)[number];

export type Session = {
  id: number;
  studentId: number;
  studentName: string;
  host: UserRef;
  mode: SessionMode;
  scheduledAt: string;
  durationMinutes: number;
  topic: string;
  meetingUrl: string | null;
  status: SessionStatus;
  notes: string | null;
  recordingConsent: boolean;
  recordingUrl: string | null;
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

export type LeadScore = { score: number; band: "HOT" | "WARM" | "COLD"; reasons: string[]; nextAction: string | null };
export type RiskScore = { score: number; risk: "HIGH" | "MEDIUM" | "LOW"; reasons: string[] };

export type PriorityLead = {
  id: number;
  fullName: string;
  phone: string;
  status: LeadStatus;
  source: LeadSource;
  assignedTo: UserRef | null;
  createdAt: string;
  score: LeadScore;
};

export type Priorities = {
  leads: PriorityLead[];
  hotLeads: PriorityLead[];
  students: { id: number; fullName: string; phone: string; counsellor: UserRef | null; risk: RiskScore }[];
};

export type AgreementKind = "SERVICE_AGREEMENT" | "DATA_CONSENT";

export type AgreementTemplate = { id: number; kind: AgreementKind; title: string; body: string; active: boolean; updatedAt: string };

export type Agreement = {
  id: number;
  studentId: number;
  studentName: string;
  kind: AgreementKind;
  title: string;
  body: string | null;
  status: "PENDING" | "SIGNED" | "CANCELLED";
  signMethod: "PORTAL_ACCEPTANCE" | "PAPER" | "AADHAAR_ESIGN" | null;
  signerName: string | null;
  signerRelation: string | null;
  signedAt: string | null;
  issuedAt: string;
  fingerprint: string;
  orgName: string;
};

export type Faq = { id: number; language: Language; question: string; answer: string; keywords: string | null; sortOrder: number; active: boolean };

export type Integration = { key: string; name: string; module: string; state: "CONNECTED" | "NOT_CONNECTED"; today: string; needs: string };
