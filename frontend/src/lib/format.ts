// Display helpers: enum labels, dates in Indian time, rupees.

const LABELS: Record<string, string> = {
  SUPER_ADMIN: "Super Admin",
  COUNSELLOR: "Counsellor",
  TELECALLER: "Telecaller",
  DOCUMENTATION_EXEC: "Documentation",
  DATA_EXEC: "Data / Research",
  ACCOUNTANT: "Accountant",
  LOAN_DESK: "Loan Desk",
  GRIEVANCE_OFFICER: "Grievance Officer",
  NEW: "New",
  QUALIFIED: "Qualified",
  FEE_PAID: "Fee Paid",
  ACTIVELY_COUNSELLED: "Actively Counselled",
  ADMISSION_CONFIRMED: "Admission Confirmed",
  CLOSED: "Closed",
  WEBSITE_PREDICTOR: "Website predictor",
  PHONE: "Phone",
  WALK_IN: "Walk-in",
  REFERRAL_ASSOCIATE: "Referral associate",
  PAST_STUDENT_REFERRAL: "Past-student referral",
  SOCIAL_ADS: "Social ads",
  SEMINAR: "Seminar / camp",
  WHATSAPP: "WhatsApp",
  OTHER: "Other",
  CALL: "Call",
  NOTE: "Note",
  MEETING: "Meeting",
  STATUS_CHANGE: "Status change",
  ASSIGNMENT: "Assignment",
  DOMICILED: "Domiciled",
  NON_DOMICILED: "Not domiciled",
  UNKNOWN: "Not confirmed",
  INDIAN: "Indian",
  NRI: "NRI",
  OCI: "OCI",
  FOREIGN: "Foreign national",
  ENGLISH: "English",
  TAMIL: "Tamil",
  HINDI: "Hindi",
  MALE: "Male",
  FEMALE: "Female",
  AIQ: "All India Quota",
  STATE: "State Quota",
  MANAGEMENT: "Management",
  MINORITY: "Minority",
  DEEMED: "Deemed",
  ROUND_1: "Round 1",
  ROUND_2: "Round 2",
  ROUND_3: "Round 3",
  MOP_UP: "Mop-up",
  STRAY_VACANCY: "Stray vacancy",
  GOVERNMENT: "Government",
  PRIVATE: "Private",
  CENTRAL: "Central",
  AIIMS: "AIIMS",
  JIPMER: "JIPMER",
  ESIC: "ESIC",
  AFMC: "AFMC",
  COLLEGE: "College",
  SEAT_MATRIX: "Seat matrix",
  FEE: "Fee",
  CUTOFF: "Cutoff",
  CREATE: "Add",
  UPDATE: "Edit",
  DELETE: "Delete",
  BULK_UPSERT: "Bulk upload",
  PENDING: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

export function label(value: string | null | undefined): string {
  if (!value) return "—";
  return LABELS[value] ?? value.charAt(0) + value.slice(1).toLowerCase().replaceAll("_", " ");
}

const dateFmt = new Intl.DateTimeFormat("en-IN", { day: "2-digit", month: "short", year: "numeric", timeZone: "Asia/Kolkata" });
const dateTimeFmt = new Intl.DateTimeFormat("en-IN", {
  day: "2-digit",
  month: "short",
  hour: "numeric",
  minute: "2-digit",
  timeZone: "Asia/Kolkata",
});
const numberFmt = new Intl.NumberFormat("en-IN");
const rupeeFmt = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

export function formatDate(iso: string | null | undefined) {
  return iso ? dateFmt.format(new Date(iso)) : "—";
}

export function formatDateTime(iso: string | null | undefined) {
  return iso ? dateTimeFmt.format(new Date(iso)) : "—";
}

export function formatNumber(n: number | null | undefined) {
  return n === null || n === undefined ? "—" : numberFmt.format(n);
}

export function formatRupees(n: number | null | undefined) {
  return n === null || n === undefined ? "—" : rupeeFmt.format(n);
}

/** "in 3 h", "2 d ago" — for follow-up due times. */
export function relativeTime(iso: string) {
  const diffMin = Math.round((new Date(iso).getTime() - Date.now()) / 60000);
  const abs = Math.abs(diffMin);
  const text = abs < 60 ? `${abs} min` : abs < 1440 ? `${Math.round(abs / 60)} h` : `${Math.round(abs / 1440)} d`;
  return diffMin >= 0 ? `in ${text}` : `${text} ago`;
}

export const INDIAN_STATES = [
  "Andaman and Nicobar Islands",
  "Andhra Pradesh",
  "Arunachal Pradesh",
  "Assam",
  "Bihar",
  "Chandigarh",
  "Chhattisgarh",
  "Dadra and Nagar Haveli and Daman and Diu",
  "Delhi",
  "Goa",
  "Gujarat",
  "Haryana",
  "Himachal Pradesh",
  "Jammu and Kashmir",
  "Jharkhand",
  "Karnataka",
  "Kerala",
  "Ladakh",
  "Lakshadweep",
  "Madhya Pradesh",
  "Maharashtra",
  "Manipur",
  "Meghalaya",
  "Mizoram",
  "Nagaland",
  "Odisha",
  "Puducherry",
  "Punjab",
  "Rajasthan",
  "Sikkim",
  "Tamil Nadu",
  "Telangana",
  "Tripura",
  "Uttar Pradesh",
  "Uttarakhand",
  "West Bengal",
];
