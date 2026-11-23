import { useState } from "react";

import { query } from "../api/client";
import { useReconciliation } from "../api/hooks";
import type { Me } from "../api/types";
import { useNames } from "../components/useNames";
import {
  Button, Card, DownloadLink, ErrorText, Field, inputClass, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass,
  thNumClass,
} from "../components/ui";
import { dateTime, hasRole } from "../lib/format";

/** A datetime-local value ("2026-11-23T14:30", the browser's time zone) as an ISO instant. */
function toInstant(local: string): string | undefined {
  return local ? new Date(local).toISOString() : undefined;
}

/** File exports (the browser downloads them with its session cookie) and the ledger audit. */
export function ReportsPage({ me }: { me: Me }) {
  const names = useNames();
  const [warehouseId, setWarehouseId] = useState("");
  const [asOf, setAsOf] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const reconciliation = useReconciliation();
  const auditor = hasRole(me, "MANAGER", "FINANCE");

  const stockParams = query({ warehouseId: warehouseId || undefined, asOf: toInstant(asOf) });
  const ledgerParams = query({ warehouseId: warehouseId || undefined, from: toInstant(from), to: toInstant(to) });

  return (
    <>
      <PageHeader title="Reports" subtitle="PDF and Excel exports. The stock report can rebuild any past moment from the ledger." />
      <div className="space-y-4">
        <Card title="Stock report">
          <div className="grid gap-3 sm:grid-cols-3">
            <Field id="rep-warehouse" label="Warehouse">
              <select id="rep-warehouse" className={inputClass} value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                <option value="">All warehouses</option>
                {names.warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
              </select>
            </Field>
            <Field id="rep-asof" label="As of (empty = now)">
              <input id="rep-asof" type="datetime-local" className={inputClass} value={asOf} onChange={(e) => setAsOf(e.target.value)} />
            </Field>
            <div className="flex items-end gap-2">
              <DownloadLink href={`/api/reports/stock.pdf${stockParams}`}>Download PDF</DownloadLink>
              <DownloadLink href={`/api/reports/stock.xlsx${stockParams}`}>Download Excel</DownloadLink>
            </div>
          </div>
        </Card>
        {auditor && (
          <Card title="Stock ledger export">
            <div className="grid gap-3 sm:grid-cols-3">
              <Field id="led-from" label="From (inclusive)">
                <input id="led-from" type="datetime-local" className={inputClass} value={from} onChange={(e) => setFrom(e.target.value)} />
              </Field>
              <Field id="led-to" label="To (exclusive)">
                <input id="led-to" type="datetime-local" className={inputClass} value={to} onChange={(e) => setTo(e.target.value)} />
              </Field>
              <div className="flex items-end">
                <DownloadLink href={`/api/reports/stock-movements.xlsx${ledgerParams}`}>Download Excel</DownloadLink>
              </div>
            </div>
            <p className="mt-2 text-xs text-slate-500">Uses the warehouse chosen above. Every movement with its running balance.</p>
          </Card>
        )}
        {auditor && (
          <Card
            title="Reconciliation"
            actions={<Button onClick={() => void reconciliation.refetch()} disabled={reconciliation.isFetching}>Run the audit</Button>}
          >
            <p className="mb-3 text-sm text-slate-600">
              Recomputes every balance, bin, running total and reservation from the append-only ledger and compares.
            </p>
            {reconciliation.isFetching && <Spinner label="Checking…" />}
            <ErrorText error={reconciliation.error} />
            {reconciliation.data && (
              <div className="space-y-3">
                <p className={`text-sm font-semibold ${reconciliation.data.balanced ? "text-emerald-700" : "text-red-700"}`}>
                  {reconciliation.data.balanced ? "Balanced" : "Discrepancies found"}: {reconciliation.data.movementsChecked} movements
                  checked at {dateTime(reconciliation.data.checkedAt)}.
                </p>
                {reconciliation.data.discrepancies.length > 0 && (
                  <TableWrap>
                    <table className={tableClass}>
                      <thead>
                        <tr>
                          <th className={thClass}>Check</th>
                          <th className={thClass}>Where</th>
                          <th className={thNumClass}>Expected</th>
                          <th className={thNumClass}>Actual</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {reconciliation.data.discrepancies.map((d, i) => (
                          <tr key={i}>
                            <td className={tdClass}>{d.check}</td>
                            <td className={tdClass}>{d.reference}{d.productId !== null && ` · ${names.sku(d.productId)}`}</td>
                            <td className={tdNumClass}>{d.expected}</td>
                            <td className={tdNumClass}>{d.actual}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </TableWrap>
                )}
                {reconciliation.data.inTransit.length > 0 && (
                  <p className="text-sm text-slate-600">
                    In transit: {reconciliation.data.inTransit.map((t) => `${names.sku(t.productId)} ${t.quantity}`).join(" · ")}
                  </p>
                )}
              </div>
            )}
          </Card>
        )}
      </div>
    </>
  );
}
