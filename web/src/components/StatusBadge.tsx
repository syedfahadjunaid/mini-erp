import { label } from "../lib/format";
import { Badge } from "./ui";

/** One colour per kind of status: waiting for someone, in progress, done, stopped. */
const looks: Record<string, string> = {
  DRAFT: "bg-slate-50 text-slate-700 ring-slate-300",
  SUBMITTED: "bg-amber-50 text-amber-800 ring-amber-300",
  APPROVED: "bg-sky-50 text-sky-800 ring-sky-300",
  CONFIRMED: "bg-sky-50 text-sky-800 ring-sky-300",
  IN_TRANSIT: "bg-sky-50 text-sky-800 ring-sky-300",
  PARTIALLY_RECEIVED: "bg-violet-50 text-violet-800 ring-violet-300",
  PARTIALLY_SHIPPED: "bg-violet-50 text-violet-800 ring-violet-300",
  RECEIVED: "bg-emerald-50 text-emerald-800 ring-emerald-300",
  SHIPPED: "bg-emerald-50 text-emerald-800 ring-emerald-300",
  RESOLVED: "bg-emerald-50 text-emerald-800 ring-emerald-300",
  CLOSED: "bg-slate-100 text-slate-700 ring-slate-300",
  CANCELLED: "bg-slate-100 text-slate-500 ring-slate-300",
  OPEN: "bg-red-50 text-red-800 ring-red-300",
  ACKNOWLEDGED: "bg-amber-50 text-amber-800 ring-amber-300",
  LOW: "bg-red-50 text-red-800 ring-red-300",
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge className={looks[status] ?? "bg-slate-50 text-slate-700 ring-slate-300"}>{label(status)}</Badge>;
}
