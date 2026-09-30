// Phase 4 DTOs: branches, marketing, sub-agents, alumni, analytics, portal access.

import type { BranchRef, CampaignRef, Course, LeadSource, LeadStatus, Quota, UserRef } from "./types";

export type { BranchRef, CampaignRef };
export type Branch = BranchRef & { city: string | null; state: string | null; active: boolean };

export type Campaign = {
  id: number;
  name: string;
  channel: LeadSource;
  branch: BranchRef | null;
  startDate: string | null;
  endDate: string | null;
  budget: number | null;
  notes: string | null;
  active: boolean;
  spent: number;
  leads: number;
  converted: number;
  admissions: number;
  costPerLead: number | null;
  costPerAdmission: number | null;
};

export type Spend = { id: number; spentOn: string; amount: number; note: string | null };

export type ChannelRow = {
  channel: LeadSource;
  leads: number;
  converted: number;
  admissions: number;
  campaignSpend: number;
  commissions: number;
  totalCost: number;
  costPerLead: number | null;
  costPerAdmission: number | null;
};

export type MarketingReport = {
  from: string;
  to: string;
  channels: ChannelRow[];
  campaigns: Campaign[];
  totalLeads: number;
  totalAdmissions: number;
  totalCost: number;
};

export type AssociateFull = {
  id: number;
  fullName: string;
  phone: string;
  district: string | null;
  commissionRate: number;
  active: boolean;
  email: string | null;
  state: string | null;
  territory: string | null;
  agreementStart: string | null;
  agreementEnd: string | null;
  agreementTerms: string | null;
  branch: BranchRef | null;
  agreementExpired: boolean;
};

export type AssociatePerformance = {
  associate: AssociateFull;
  leads: number;
  converted: number;
  admissions: number;
  commissionPending: number;
  commissionApproved: number;
  commissionPaid: number;
};

export type AlumniRow = {
  id: number;
  studentId: number;
  studentName: string;
  phone: string;
  collegeId: number | null;
  collegeName: string;
  course: Course;
  quota: Quota | null;
  admissionYear: number;
  willingToRefer: boolean;
  counsellor: UserRef | null;
  branch: BranchRef | null;
  latestRating: number | null;
  wouldRecommend: boolean | null;
  referrals: number;
  referralAdmissions: number;
};

export type AlumniSummary = {
  total: number;
  willingToRefer: number;
  surveyed: number;
  averageRating: number | null;
  recommendPercent: number | null;
  referrals: number;
  referralAdmissions: number;
  testimonialsPending: number;
};

export type AlumniDirectory = { summary: AlumniSummary; rows: AlumniRow[] };

export type Survey = {
  id: number;
  overallRating: number;
  counsellorRating: number | null;
  wouldRecommend: boolean;
  comments: string | null;
  recordedBy: UserRef | null;
  recordedAt: string;
};

export type TestimonialStatus = "PENDING" | "APPROVED" | "REJECTED";

export type Testimonial = {
  id: number;
  quote: string;
  consent: boolean;
  status: TestimonialStatus;
  recordedBy: UserRef | null;
  reviewedBy: UserRef | null;
  createdAt: string;
};

export type AlumniDetail = {
  alumni: AlumniRow;
  notes: string | null;
  surveys: Survey[];
  testimonials: Testimonial[];
  referrals: { leadId: number; leadName: string; status: LeadStatus; createdAt: string }[];
};

export type OutcomeRow = { key: string; students: number; allotted: number; admitted: number };

export type AnalyticsOverview = {
  from: string;
  to: string;
  roundDay: { awaitingResults: number; decisionsPending: number; decisionsClosing24h: number; unacknowledgedUrgent: number };
  funnel: { stage: string; count: number }[];
  leadsByStatus: Record<LeadStatus, number>;
  revenue: {
    billed: number;
    collected: number;
    refunded: number;
    outstanding: number;
    monthly: { month: string; collected: number }[];
  };
  byCategory: OutcomeRow[];
  byState: OutcomeRow[];
  branches: { branch: BranchRef | null; leads: number; admissions: number; students: number; collected: number }[];
};

export const REPORT_DATASETS = ["LEADS", "STUDENTS", "PAYMENTS", "ADMISSIONS", "ALLOTMENTS", "TICKETS"] as const;
export type ReportDataset = (typeof REPORT_DATASETS)[number];

export type ReportResult = {
  dataset: ReportDataset;
  from: string;
  to: string;
  columns: { key: string; label: string }[];
  rows: (string | number | null)[][];
  truncated: boolean;
};

export type PortalRelation = "STUDENT" | "PARENT";

export type PortalAccess = {
  accountId: number;
  phone: string;
  displayName: string;
  relation: PortalRelation;
  activated: boolean;
  active: boolean;
  codePending: boolean;
  codeExpiresAt: string | null;
  lastLoginAt: string | null;
};

export type PortalGranted = { access: PortalAccess; activationCode: string | null };
