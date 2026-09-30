// Mirrors the backend DTOs. Keep enum lists in the same order as the Java enums.

export const ROLES = [
  "SUPER_ADMIN",
  "COUNSELLOR",
  "TELECALLER",
  "DOCUMENTATION_EXEC",
  "DATA_EXEC",
  "ACCOUNTANT",
  "LOAN_DESK",
  "GRIEVANCE_OFFICER",
] as const;
export type Role = (typeof ROLES)[number];

export const CATEGORIES = ["GEN", "EWS", "OBC", "SC", "ST"] as const;
export type Category = (typeof CATEGORIES)[number];

export const QUOTAS = ["AIQ", "STATE", "MANAGEMENT", "NRI", "MINORITY", "DEEMED"] as const;
export type Quota = (typeof QUOTAS)[number];

export const COURSES = ["MBBS", "BDS"] as const;
export type Course = (typeof COURSES)[number];

export const ROUNDS = ["ROUND_1", "ROUND_2", "ROUND_3", "MOP_UP", "STRAY_VACANCY"] as const;
export type CounsellingRound = (typeof ROUNDS)[number];

export const COLLEGE_TYPES = ["GOVERNMENT", "PRIVATE", "DEEMED", "CENTRAL", "AIIMS", "JIPMER", "ESIC", "AFMC"] as const;
export type CollegeType = (typeof COLLEGE_TYPES)[number];

export const DOMICILE = ["DOMICILED", "NON_DOMICILED", "UNKNOWN"] as const;
export type DomicileStatus = (typeof DOMICILE)[number];

export const NATIONALITIES = ["INDIAN", "NRI", "OCI", "FOREIGN"] as const;
export type Nationality = (typeof NATIONALITIES)[number];

export const LANGUAGES = ["ENGLISH", "TAMIL", "HINDI"] as const;
export type Language = (typeof LANGUAGES)[number];

export const GENDERS = ["MALE", "FEMALE", "OTHER"] as const;
export type Gender = (typeof GENDERS)[number];

export const LEAD_STATUSES = [
  "NEW",
  "QUALIFIED",
  "FEE_PAID",
  "ACTIVELY_COUNSELLED",
  "ADMISSION_CONFIRMED",
  "CLOSED",
] as const;
export type LeadStatus = (typeof LEAD_STATUSES)[number];

export const LEAD_SOURCES = [
  "WEBSITE_PREDICTOR",
  "PHONE",
  "WALK_IN",
  "REFERRAL_ASSOCIATE",
  "PAST_STUDENT_REFERRAL",
  "SOCIAL_ADS",
  "SEMINAR",
  "WHATSAPP",
  "OTHER",
] as const;
export type LeadSource = (typeof LEAD_SOURCES)[number];

export const MANUAL_ACTIVITY_TYPES = ["CALL", "NOTE", "WHATSAPP", "MEETING"] as const;
export type ActivityType = (typeof MANUAL_ACTIVITY_TYPES)[number] | "STATUS_CHANGE" | "ASSIGNMENT";

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type UserRef = { id: number; fullName: string; role: Role };
export type BranchRef = { id: number; name: string; code: string };
export type CampaignRef = { id: number; name: string; channel: LeadSource };

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

export type LeadListItem = {
  id: number;
  fullName: string;
  phone: string;
  neetScore: number | null;
  neetAir: number | null;
  category: Category | null;
  homeState: string | null;
  source: LeadSource;
  status: LeadStatus;
  assignedCounsellor: UserRef | null;
  studentId: number | null;
  branch: BranchRef | null;
  campaign: CampaignRef | null;
  createdAt: string;
};

export type Lead = {
  id: number;
  fullName: string;
  phone: string;
  altPhone: string | null;
  email: string | null;
  neetRollNo: string | null;
  neetScore: number | null;
  neetAir: number | null;
  category: Category | null;
  homeState: string | null;
  domicileStatus: DomicileStatus | null;
  source: LeadSource;
  referralAssociate: { id: number; fullName: string } | null;
  status: LeadStatus;
  assignedCounsellor: UserRef | null;
  languagePreference: Language;
  notes: string | null;
  studentId: number | null;
  branch: BranchRef | null;
  campaign: CampaignRef | null;
  referredByStudent: { id: number; fullName: string } | null;
  createdAt: string;
  updatedAt: string;
};

export type LeadRequest = {
  fullName: string;
  phone: string;
  altPhone?: string | null;
  email?: string | null;
  neetRollNo?: string | null;
  neetScore?: number | null;
  neetAir?: number | null;
  category?: Category | null;
  homeState?: string | null;
  domicileStatus?: DomicileStatus | null;
  source: LeadSource;
  referralAssociateId?: number | null;
  languagePreference?: Language;
  notes?: string | null;
  assignedCounsellorId?: number | null;
  allowDuplicatePhone?: boolean;
  branchId?: number | null;
  campaignId?: number | null;
  referredByStudentId?: number | null;
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
  leadId: number;
  leadName: string;
  leadPhone: string;
  assignedTo: UserRef;
  dueAt: string;
  purpose: string;
  completedAt: string | null;
  overdue: boolean;
};

export type ImportResult = { imported: number; skippedDuplicates: number; errors: { row: number; message: string }[] };

export type QuotaFlag = { quota: Quota; eligible: boolean; reason: string };
export type Eligibility = { quotas: QuotaFlag[]; warnings: string[]; disclaimer: string };

export type StudentListItem = {
  id: number;
  fullName: string;
  phone: string;
  category: Category;
  homeState: string;
  neetScore: number | null;
  neetAir: number | null;
  assignedCounsellor: UserRef | null;
  branch: BranchRef | null;
  createdAt: string;
};

export type StudentRequest = {
  fullName: string;
  dateOfBirth: string | null;
  gender: Gender | null;
  phone: string;
  email: string | null;
  parentName: string | null;
  parentPhone: string | null;
  category: Category;
  pwd: boolean;
  homeState: string;
  domicileStatus: DomicileStatus;
  nationality: Nationality;
  nriSponsored: boolean;
  neetYear: number | null;
  neetRollNo: string | null;
  neetQualified: boolean;
  neetScore: number | null;
  neetPercentile: number | null;
  neetAir: number | null;
  categoryRank: number | null;
  categoryCertValidUntil: string | null;
  languagePreference: Language;
  apaarId: string | null;
  assignedCounsellorId: number | null;
  branchId: number | null;
};

export type Student = Omit<StudentRequest, "assignedCounsellorId" | "branchId"> & {
  id: number;
  assignedCounsellor: UserRef | null;
  branch: BranchRef | null;
  leadId: number | null;
  eligibility: Eligibility;
  createdAt: string;
  updatedAt: string;
};

export type Associate = {
  id: number;
  fullName: string;
  phone: string;
  district: string | null;
  commissionRate: number;
  active: boolean;
};

export type College = {
  id: number;
  name: string;
  code: string | null;
  collegeType: CollegeType;
  state: string;
  city: string | null;
  affiliatedUniversity: string | null;
  nmcRecognized: boolean;
  establishedYear: number | null;
  website: string | null;
  updatedAt: string;
};

export type SeatRow = {
  id: number;
  collegeId: number;
  course: Course;
  quota: Quota;
  category: Category;
  pwd: boolean;
  counsellingRound: CounsellingRound;
  academicYear: number;
  seats: number;
};

export type FeeRow = {
  id: number;
  collegeId: number;
  course: Course;
  quota: Quota;
  academicYear: number;
  annualTuition: number;
  otherFees: number | null;
  notes: string | null;
};

export type CutoffRow = Omit<SeatRow, "seats"> & { closingRank: number };

export type CollegeDetail = { college: College; seatMatrix: SeatRow[]; fees: FeeRow[]; cutoffs: CutoffRow[] };

export type ChangeEntity = "COLLEGE" | "SEAT_MATRIX" | "FEE" | "CUTOFF" | "REFUND_RULE";
export type ChangeAction = "CREATE" | "UPDATE" | "DELETE" | "BULK_UPSERT";
export type ChangeStatus = "PENDING" | "APPROVED" | "REJECTED";

export type ChangeRequest = {
  id: number;
  entityType: ChangeEntity;
  entityId: number | null;
  action: ChangeAction;
  summary: string;
  status: ChangeStatus;
  requestedBy: UserRef;
  requestedAt: string;
  reviewedBy: UserRef | null;
  reviewedAt: string | null;
  reviewNote: string | null;
};

export type ChangeRequestDetail = { request: ChangeRequest; payload: unknown; current: unknown };

export type DashboardSummary = {
  showsLeads: boolean;
  leadsByStatus: Partial<Record<LeadStatus, number>>;
  leadsBySource: Partial<Record<LeadSource, number>>;
  totalLeads: number;
  newLeadsLast7Days: number;
  followUpsDueToday: number;
  followUpsOverdue: number;
  students: number | null;
  colleges: number;
  pendingApprovals: number | null;
};
