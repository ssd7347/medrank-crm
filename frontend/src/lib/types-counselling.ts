// Phase 2 DTOs: counselling calendar, tracks, choice lists, allotments, predictor, alerts.

import type { Category, CollegeType, CounsellingRound, Course, Quota, UserRef } from "./types";

export const TRACK_STATUSES = ["NOT_REGISTERED", "REGISTERED", "CHOICES_FILLED", "ALLOTTED", "ADMITTED", "EXITED"] as const;
export type TrackStatus = (typeof TRACK_STATUSES)[number];

export const CONFIRMATION_METHODS = ["IN_PERSON", "PHONE", "WHATSAPP", "VIDEO"] as const;
export type ConfirmationMethod = (typeof CONFIRMATION_METHODS)[number];

export const DECISIONS = ["FREEZE", "FLOAT", "WITHDRAW"] as const;
export type Decision = (typeof DECISIONS)[number];

export type AuthorityType = "CENTRAL" | "STATE";

export type Authority = {
  id: number;
  code: string;
  name: string;
  authorityType: AuthorityType;
  state: string | null;
  website: string | null;
  active: boolean;
};

export type AuthorityRef = Pick<Authority, "id" | "code" | "name" | "authorityType" | "state">;

export type RoundPhase = "UPCOMING" | "REGISTRATION" | "CHOICE_FILLING" | "AWAITING_RESULT" | "RESULT_OUT" | "REPORTING" | "CLOSED";

export type Round = {
  id: number;
  authority: AuthorityRef;
  academicYear: number;
  roundType: CounsellingRound;
  label: string;
  registrationStart: string | null;
  registrationEnd: string | null;
  choiceFillingStart: string | null;
  choiceFillingEnd: string | null;
  resultAt: string | null;
  reportingStart: string | null;
  reportingEnd: string | null;
  notes: string | null;
  phase: RoundPhase;
};

export type Deadline = { roundId: number; roundLabel: string; kind: string; at: string };

export type CollegeRef = { id: number; name: string; code: string | null; state: string; collegeType: CollegeType };

export type ChoiceListSummary = { id: number; roundId: number; roundLabel: string; status: "DRAFT" | "LOCKED"; items: number; lockedAt: string | null };

export type Allotment = {
  id: number;
  trackId: number;
  roundId: number;
  roundLabel: string;
  roundType: CounsellingRound;
  college: CollegeRef | null;
  course: Course | null;
  quota: Quota | null;
  category: Category | null;
  recordedBy: UserRef | null;
  recordedAt: string;
  decision: Decision | null;
  decisionDeadline: string | null;
  decidedAt: string | null;
  decidedBy: UserRef | null;
  decisionNote: string | null;
};

export type Track = {
  id: number;
  studentId: number;
  authority: AuthorityRef;
  academicYear: number;
  registrationNo: string | null;
  status: TrackStatus;
  rounds: Round[];
  choiceLists: ChoiceListSummary[];
  allotments: Allotment[];
};

export type ChoiceItem = { id: number; position: number; college: CollegeRef; course: Course; quota: Quota; note: string | null };

export type ChoiceList = {
  id: number;
  trackId: number;
  studentId: number;
  studentName: string;
  authority: AuthorityRef;
  round: Round;
  status: "DRAFT" | "LOCKED";
  lockedAt: string | null;
  lockedBy: UserRef | null;
  confirmedByName: string | null;
  confirmationMethod: ConfirmationMethod | null;
  items: ChoiceItem[];
  updatedAt: string;
};

export type DecisionPreview = { decision: Decision; consequences: string[]; pastDeadline: boolean; refundRuleFound: boolean };

export type DeskRow = {
  allotmentId: number;
  studentId: number;
  studentName: string;
  studentPhone: string;
  counsellor: UserRef | null;
  roundLabel: string;
  college: CollegeRef;
  quota: Quota;
  decisionDeadline: string;
};

export type RoundDesk = { upcomingDeadlines: Deadline[]; decisionsPending: DeskRow[]; unacknowledgedEscalations: number };

export type Band = "HIGH" | "MODERATE" | "LOW";

export type Prediction = {
  collegeId: number;
  collegeName: string;
  collegeCode: string | null;
  state: string;
  city: string | null;
  collegeType: CollegeType;
  quota: Quota;
  course: Course;
  band: Band;
  latestYear: number;
  lastClosingRank: number;
  history: { year: number; round: CounsellingRound; closingRank: number }[];
  annualTuition: number | null;
  feeYear: number | null;
};

export type PredictResponse = {
  rank: number;
  category: Category;
  pwd: boolean;
  course: Course;
  quotas: Quota[];
  dataYears: number[];
  results: Prediction[];
  truncated: boolean;
  disclaimer: string;
};

export type ShortlistItem = {
  id: number;
  collegeId: number;
  collegeName: string;
  state: string;
  collegeType: CollegeType;
  course: Course;
  quota: Quota;
  band: Band | null;
  note: string | null;
  createdAt: string;
};

export type AppNotification = {
  id: number;
  studentId: number | null;
  type: string;
  priority: "NORMAL" | "URGENT";
  title: string;
  body: string | null;
  link: string | null;
  createdAt: string;
  readAt: string | null;
};

export type OutboundMessage = {
  id: number;
  channel: "WHATSAPP" | "SMS" | "EMAIL";
  recipient: string;
  recipientLabel: "STUDENT" | "PARENT";
  template: string;
  priority: "NORMAL" | "URGENT";
  body: string;
  status: "QUEUED" | "SENT" | "SIMULATED" | "FAILED";
  attempts: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
  acknowledgedAt: string | null;
  escalatedAt: string | null;
};
