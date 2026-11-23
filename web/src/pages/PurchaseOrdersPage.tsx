import { useState } from "react";
import { Link } from "react-router";

import { usePurchaseOrders } from "../api/hooks";
import type { Me, PurchaseOrderStatus } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Card, EmptyState, ErrorText, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass, thNumClass,
} from "../components/ui";
import { dateTime, hasRole, label } from "../lib/format";

const FILTERS: (PurchaseOrderStatus | undefined)[] = [undefined, "DRAFT", "SUBMITTED", "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED"];

export function PurchaseOrdersPage({ me }: { me: Me }) {
  const [status, setStatus] = useState<PurchaseOrderStatus | undefined>(undefined);
  const orders = usePurchaseOrders(status);
  const names = useNames();

  return (
    <>
      <PageHeader
        title="Purchase orders"
        subtitle="Raised by purchasing, approved by a manager (never the person who raised it), received by the warehouse."
        actions={hasRole(me, "PURCHASING") && (
          <Link to="/purchase-orders/new" className="rounded-md bg-teal-700 px-3 py-1.5 text-sm font-medium text-white shadow-sm hover:bg-teal-800">
            New purchase order
          </Link>
        )}
      />
      <div className="mb-4 flex flex-wrap gap-1" role="group" aria-label="Filter by status">
        {FILTERS.map((s) => (
          <button
            key={s ?? "all"}
            type="button"
            onClick={() => setStatus(s)}
            className={`rounded-full px-3 py-1 text-xs font-medium ring-1 ${status === s ? "bg-teal-700 text-white ring-teal-700" : "bg-white text-slate-700 ring-slate-300 hover:bg-slate-50"}`}
          >
            {s ? label(s) : "All"}
          </button>
        ))}
      </div>
      <Card>
        {orders.isPending && <Spinner />}
        <ErrorText error={orders.error} />
        {orders.data && orders.data.length === 0 && <EmptyState>No purchase orders here yet.</EmptyState>}
        {orders.data && orders.data.length > 0 && (
          <TableWrap>
            <table className={tableClass}>
              <thead>
                <tr>
                  <th className={thClass}>Order</th>
                  <th className={thClass}>Supplier</th>
                  <th className={thClass}>Warehouse</th>
                  <th className={thClass}>Status</th>
                  <th className={thNumClass}>Total</th>
                  <th className={thClass}>Raised</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {orders.data.map((o) => (
                  <tr key={o.id} className="hover:bg-slate-50">
                    <td className={tdClass}>
                      <Link to={`/purchase-orders/${o.id}`} className="font-medium text-teal-800 hover:underline">{o.orderNumber}</Link>
                    </td>
                    <td className={tdClass}>{names.supplier(o.supplierId)}</td>
                    <td className={tdClass}>{names.warehouse(o.warehouseId)}</td>
                    <td className={tdClass}><StatusBadge status={o.status} /></td>
                    <td className={tdNumClass}>{o.total.formatted} {o.currency}</td>
                    <td className={tdClass}>{dateTime(o.createdAt)} · {o.createdBy}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        )}
      </Card>
    </>
  );
}
