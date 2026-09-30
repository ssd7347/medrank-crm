// Phase 3 DTOs: documents, fees, commissions, tickets, grievances, refund rules, staff.

import type { CounsellingRound, Role, UserRef } from "./types";

export const DOCUMENT_STATUSES = ["NOT_COLLECTED", "COLLECTED", "VERIFIED", "SUBMITTED", "REJECTED"] as const;
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number];

export const APPLIES_WHEN = ["ALWAYS", "RESERVED_CATEGORY", "EWS", "DOMICILED", "PWD", "NRI", "OPTIONAL"] as const;
export type AppliesWhen = (typeof APPLIES_WHEN)[number];

export type DocFile = { id: number; originalName: string; contentType: string; sizeBytes: number; uploadedBy: UserRef | null; uploadedAt: string; fromPortal: boolean };

export type ChecklistItem = {
  typeId: number;
  code: string;
  name: string;
  required: boolean;
  requiresExpiry: boolean;
  documentId: number | null;
  status: DocumentStatus;
  validUntil: string | null;
  notes: string | null;
  verifiedBy: UserRef | null;
  verifiedAt: string | null;
  expired: boolean;
  expiringSoon: boolean;
  files: DocFile[];
};

export type Checklist = { studentId: number; requiredCount: number; requiredDone: number; missingRequired: string[]; items: ChecklistItem[] };

export type QueueRow = {
  documentId: number;
  studentId: number;
  studentName: string;
  counsellor: UserRef | null;
  typeName: string;
  status: DocumentStatus;
  validUntil: string | null;
  updatedAt: string;
};

export type DocType = { id: number; code: string; name: string; appliesWhen: AppliesWhen; requiresExpiry: boolean; sortOrder: number; active: boolean };

export const PAYMENT_METHODS = ["CASH", "UPI", "BANK_TRANSFER", "CHEQUE", "CARD", "GATEWAY"] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export type InstallmentView = { id: number; seq: number; label: string; amount: number; dueDate: string; paid: number; balance: number; state: "PAID" | "PARTIAL" | "DUE" | "OVERDUE" };

export type PaymentView = {
  id: number;
  receiptNo: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  paidOn: string;
  notes: string | null;
  receivedBy: UserRef | null;
  voided: boolean;
  voidReason: string | null;
};

export type RefundStatus = "REQUESTED" | "APPROVED" | "REJECTED" | "PAID";

export type RefundView = {
  id: number;
  planId: number;
  amount: number;
  reason: string;
  status: RefundStatus;
  requestedBy: UserRef | null;
  requestedAt: string;
  decidedBy: UserRef | null;
  decidedAt: string | null;
  decisionNote: string | null;
  paidOn: string | null;
  paidReference: string | null;
};

export type PlanView = {
  id: number;
  name: string;
  packageName: string | null;
  status: "ACTIVE" | "CLOSED" | "CANCELLED";
  totalAmount: number;
  discount: number;
  netAmount: number;
  paid: number;
  refunded: number;
  balance: number;
  notes: string | null;
  createdAt: string;
  installments: InstallmentView[];
  payments: PaymentView[];
  refunds: RefundView[];
};

export type PackageView = {
  id: number;
  name: string;
  description: string | null;
  totalAmount: number;
  active: boolean;
  installments: { label: string; amount: number; dueOffsetDays: number }[];
};

export type Receipt = {
  orgName: string;
  receiptNo: string;
  paidOn: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  studentName: string;
  studentPhone: string;
  planName: string;
  planNet: number;
  paidToDate: number;
  balance: number;
  receivedBy: string | null;
  voided: boolean;
  voidReason: string | null;
};

export type DueRow = {
  installmentId: number;
  planId: number;
  studentId: number;
  studentName: string;
  studentPhone: string;
  counsellor: UserRef | null;
  label: string;
  dueDate: string;
  balance: number;
  overdueDays: number;
};

export type DuesSummary = { outstanding: number; overdue: number; dueNext7Days: number; collectedThisMonth: number; rows: DueRow[] };

export type CommissionView = {
  id: number;
  associateId: number;
  associateName: string;
  leadId: number;
  leadName: string;
  studentId: number | null;
  basisAmount: number;
  rate: number;
  amount: number;
  status: "PENDING" | "APPROVED" | "PAID" | "CANCELLED";
  createdAt: string;
  paidOn: string | null;
  paidReference: string | null;
};

export const CHANNELS = ["PHONE", "WHATSAPP", "WALK_IN", "EMAIL", "PORTAL", "INTERNAL"] as const;
export type Channel = (typeof CHANNELS)[number];
export const TICKET_STATUSES = ["OPEN", "IN_PROGRESS", "WAITING_ON_STUDENT", "RESOLVED", "CLOSED"] as const;
export type TicketStatus = (typeof TICKET_STATUSES)[number];
export const TICKET_PRIORITIES = ["LOW", "NORMAL", "HIGH", "URGENT"] as const;
export type TicketPriority = (typeof TICKET_PRIORITIES)[number];
export const TICKET_CATEGORIES = ["GENERAL", "DOCUMENTS", "FEES", "COUNSELLING", "TECHNICAL", "OTHER"] as const;
export type TicketCategory = (typeof TICKET_CATEGORIES)[number];

export type TicketView = {
  id: number;
  studentId: number | null;
  studentName: string | null;
  leadId: number | null;
  raisedByName: string | null;
  raisedVia: Channel;
  subject: string;
  description: string | null;
  category: TicketCategory;
  priority: TicketPriority;
  deadlineLinked: boolean;
  status: TicketStatus;
  assignedTo: UserRef | null;
  dueAt: string;
  overdue: boolean;
  resolution: string | null;
  resolvedAt: string | null;
  createdBy: UserRef | null;
  createdAt: string;
  comments: { id: number; author: UserRef | null; body: string; createdAt: string }[];
};

export const GRIEVANCE_CATEGORIES = ["FEE_REFUND", "ALLOTMENT", "SERVICE_QUALITY", "STAFF_CONDUCT", "OTHER"] as const;
export type GrievanceCategory = (typeof GRIEVANCE_CATEGORIES)[number];
export type GrievanceStatus = "OPEN" | "UNDER_REVIEW" | "ESCALATED" | "RESOLVED" | "CLOSED";
export type GrievanceActionType = "CREATED" | "NOTE" | "CONTACTED" | "ASSIGNED" | "STATUS_CHANGE" | "ESCALATED" | "RESOLVED";

export type GrievanceView = {
  id: number;
  referenceNo: string;
  studentId: number | null;
  studentName: string | null;
  ticketId: number | null;
  complainantName: string;
  complainantPhone: string | null;
  category: GrievanceCategory;
  description: string;
  amountInDispute: number | null;
  receivedVia: Channel;
  receivedAt: string;
  status: GrievanceStatus;
  assignedOfficer: UserRef | null;
  targetResolutionDate: string;
  overdue: boolean;
  resolution: string | null;
  resolvedAt: string | null;
  createdBy: UserRef | null;
  createdAt: string;
  trail: { id: number; actor: UserRef | null; type: GrievanceActionType; details: string | null; createdAt: string }[];
};

export type RefundRuleView = {
  id: number;
  authorityId: number | null;
  collegeId: number | null;
  roundType: CounsellingRound | null;
  academicYear: number;
  maxDaysAfterAllotment: number | null;
  depositForfeited: boolean;
  depositAmount: number | null;
  tuitionRefundPercent: number;
  barredFromLaterRounds: boolean;
  source: string;
  notes: string | null;
  updatedAt: string;
};

export type StaffRow = {
  userId: number;
  fullName: string;
  role: Role;
  openLeads: number;
  newLeadsInPeriod: number;
  convertedToStudent: number;
  admissionsConfirmed: number;
  admissionRate: number;
  studentsAssigned: number;
  overdueFollowUps: number;
  openTickets: number;
};
