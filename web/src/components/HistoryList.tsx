import type { HistoryEntry } from "../api/types";
import { dateTime, label } from "../lib/format";
import { EmptyState } from "./ui";

/** A document's status changes, oldest first: who did what, when. */
export function HistoryList({ entries }: { entries: HistoryEntry[] }) {
  if (entries.length === 0) return <EmptyState>No history yet.</EmptyState>;
  return (
    <ol className="space-y-1.5 text-sm">
      {entries.map((h, i) => (
        <li key={i} className="flex flex-wrap gap-x-2 text-slate-700">
          <span className="w-40 shrink-0 text-slate-500">{dateTime(h.occurredAt)}</span>
          <span className="font-medium">{label(h.action)}</span>
          <span>by {h.actor}</span>
          <span className="text-slate-500">
            {h.fromStatus ? `${label(h.fromStatus)} → ` : ""}{label(h.toStatus)}
          </span>
          {h.comment && <span className="text-slate-500">· {h.comment}</span>}
        </li>
      ))}
    </ol>
  );
}
