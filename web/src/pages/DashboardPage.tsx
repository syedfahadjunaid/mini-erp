import { Link } from "react-router";

import { useAcknowledgeAlert, useAlerts, usePurchaseOrders, useSalesOrders, useTransfers } from "../api/hooks";
import type { Me } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import {
  Button, Card, EmptyState, ErrorText, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass, thNumClass,
} from "../components/ui";
import { dateTime, hasRole } from "../lib/format";

/** What needs attention now: low stock, orders waiting for someone, goods on the road. */
export function DashboardPage({ me }: { me: Me }) {
  const open = useAlerts("OPEN");
  const acknowledged = useAlerts("ACKNOWLEDGED");
  const toApprove = usePurchaseOrders("SUBMITTED");
  const toReceive = usePurchaseOrders("APPROVED");
  const confirmed = useSalesOrders("CONFIRMED");
  const partlyShipped = useSalesOrders("PARTIALLY_SHIPPED");
  const transfers = useTransfers();
  const acknowledge = useAcknowledgeAlert();

  const alerts = [...(open.data ?? []), ...(acknowledged.data ?? [])];
  const toShip = [...(confirmed.data ?? []), ...(partlyShipped.data ?? [])];
  const inTransit = transfers.data?.filter((t) => t.status === "IN_TRANSIT") ?? [];
  const canAcknowledge = hasRole(me, "MANAGER", "PURCHASING");

  return (
    <>
      <PageHeader title={`Hello, ${me.displayName}`} subtitle="What needs attention across the warehouses." />
      <div className="mb-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Tile label="Low-stock alerts" value={alerts.length} to="/" tone={alerts.length ? "red" : "slate"} />
        <Tile label="Purchase orders to approve" value={toApprove.data?.length} to="/purchase-orders" />
        <Tile label="Purchase orders to receive" value={toReceive.data?.length} to="/purchase-orders" />
        <Tile label="Sales orders to ship" value={toShip.length} to="/sales-orders" />
      </div>
      <div className="space-y-4">
        <Card title="Low-stock alerts" actions={<span className="text-xs text-slate-500">Raised and cleared automatically as stock moves</span>}>
          {open.isPending && <Spinner />}
          <ErrorText error={open.error ?? acknowledge.error} />
          {!open.isPending && alerts.length === 0 && <EmptyState>Every product with a reorder point is above it.</EmptyState>}
          {alerts.length > 0 && (
            <TableWrap>
              <table className={tableClass}>
                <thead>
                  <tr>
                    <th className={thClass}>Product</th>
                    <th className={thClass}>Warehouse</th>
                    <th className={thNumClass}>Available now</th>
                    <th className={thNumClass}>Reorder point</th>
                    <th className={thNumClass}>Suggested order</th>
                    <th className={thClass}>Status</th>
                    <th className={thClass}>Raised</th>
                    <th className={thClass} />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {alerts.map((a) => (
                    <tr key={a.id}>
                      <td className={tdClass}><span className="font-medium">{a.sku}</span> · {a.productName}</td>
                      <td className={tdClass}>{a.warehouseCode}</td>
                      <td className={`${tdNumClass} font-semibold text-red-700`}>{a.availableNow}</td>
                      <td className={tdNumClass}>{a.reorderPoint}</td>
                      <td className={tdNumClass}>{a.reorderQuantity}</td>
                      <td className={tdClass}>
                        <StatusBadge status={a.status} />
                        {a.acknowledgedBy && <span className="ml-1 text-xs text-slate-500">by {a.acknowledgedBy}</span>}
                      </td>
                      <td className={tdClass}>{dateTime(a.raisedAt)}</td>
                      <td className={tdClass}>
                        {canAcknowledge && a.status === "OPEN" && (
                          <Button variant="secondary" disabled={acknowledge.isPending} onClick={() => acknowledge.mutate(a.id)}>
                            Acknowledge
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableWrap>
          )}
        </Card>
        <div className="grid gap-4 lg:grid-cols-2">
          <Card title="Waiting for approval">
            {toApprove.data?.length === 0 && <EmptyState>No purchase orders waiting.</EmptyState>}
            <ul className="divide-y divide-slate-100 text-sm">
              {toApprove.data?.map((o) => (
                <li key={o.id} className="flex justify-between py-2">
                  <Link to={`/purchase-orders/${o.id}`} className="font-medium text-teal-800 hover:underline">{o.orderNumber}</Link>
                  <span>{o.total.formatted} {o.currency} · by {o.createdBy}</span>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="In transit between warehouses">
            {inTransit.length === 0 && <EmptyState>Nothing on the road.</EmptyState>}
            <ul className="divide-y divide-slate-100 text-sm">
              {inTransit.map((t) => (
                <li key={t.id} className="flex justify-between py-2">
                  <Link to="/transfers" className="font-medium text-teal-800 hover:underline">{t.transferNumber}</Link>
                  <span>{t.lines.reduce((sum, l) => sum + l.quantity, 0)} units · since {dateTime(t.createdAt)}</span>
                </li>
              ))}
            </ul>
          </Card>
        </div>
      </div>
    </>
  );
}

function Tile({ label, value, to, tone = "slate" }: { label: string; value: number | undefined; to: string; tone?: "slate" | "red" }) {
  return (
    <Link to={to} className="rounded-lg bg-white p-4 shadow-sm ring-1 ring-slate-200 hover:ring-teal-400">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${tone === "red" ? "text-red-700" : "text-slate-900"}`}>{value ?? "…"}</p>
    </Link>
  );
}
