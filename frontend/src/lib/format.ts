// Display helpers: enum labels, dates in Indian time, rupees.

const LABELS: Record<string, string> = {
  SUPER_ADMIN: "Admin",
  MANAGER: "Manager",
  AGENT: "Agent",
  NEW: "New",
  CONTACTED: "Contacted",
  INTERESTED: "Interested",
  CUSTOMER: "Customer",
  NOT_INTERESTED: "Not interested",
  DO_NOT_CALL: "Do not call",
  CALL: "Call",
  NOTE: "Note",
  WHATSAPP: "WhatsApp",
  EMAIL: "Email",
  MEETING: "Meeting",
  STATUS_CHANGE: "Status change",
  ASSIGNMENT: "Assignment",
  IMPORT: "Import",
  MERGE: "Merged",
  TEXT: "Text",
  NUMBER: "Number",
  AMOUNT: "Amount (₹)",
  DATE: "Date",
  CONNECTED: "Connected",
  NO_ANSWER: "No answer",
  BUSY: "Busy",
  SWITCHED_OFF: "Switched off",
  CALL_BACK_LATER: "Asked to call back",
  WRONG_NUMBER: "Wrong number",
  OPEN: "Open",
  ASSIGNED: "Assigned",
  DONE: "Done",
  DRAFT: "Draft",
  RUNNING: "Running",
  PAUSED: "Paused",
  PENDING: "Pending",
  SKIPPED: "Skipped",
  FAILED: "Failed",
  COMPLAINT_OR_DISPUTE: "Complaint or dispute",
  DISTRESS: "Sounded distressed",
  CALLER_REQUEST: "Asked for a person",
  TOOL_FAILURE: "Agent could not look something up",
  VERIFICATION_FAILED: "Identity not verified",
  OUT_OF_SCOPE: "Question the agent could not answer",
  CALLBACK_REQUESTED: "Asked to be called back",
  WILL_ACT: "Promised to act",
  ACKNOWLEDGED: "Acknowledged",
  HANDED_OFF: "Handed to a person",
  OPTED_OUT: "Asked to stop",
  WRONG_PERSON: "Wrong person",
  NO_INTERACTION: "No conversation",
  WEB_FORM: "Website form",
  SIGNUP_FORM: "Sign-up form",
  VERBAL_ON_CALL: "Said yes on a recorded call",
  PAPER_FORM: "Signed paper form",
  IMPORTED_LIST: "Consent given with an imported list",
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

/** ISO instant -> value for <input type="datetime-local"> in the browser's time zone (IST for staff). */
export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** <input type="datetime-local"> value -> ISO instant, or null when empty. */
export function fromLocalInput(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}

/** "in 2 d 4 h" style countdown for deadlines. */
export function countdown(iso: string): string {
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return "passed";
  const h = Math.floor(ms / 3_600_000);
  if (h < 1) return `in ${Math.max(1, Math.round(ms / 60_000))} min`;
  if (h < 6) return `in ${h} h ${Math.floor((ms % 3_600_000) / 60_000)} min`;
  if (h < 48) return `in ${h} h`;
  return `in ${Math.floor(h / 24)} d ${h % 24} h`;
}
