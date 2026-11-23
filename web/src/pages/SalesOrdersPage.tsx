import { useState } from "react";
import { Link } from "react-router";

import { useSalesOrders } from "../api/hooks";
import type { Me, SalesOrderStatus } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Card, EmptyState, ErrorText, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass, thNumClass,
} from "../components/ui";
import { dateTime, hasRole, label } from "../lib/format";

const FILTERS: (SalesOrderStatus | undefined)[] = [undefined, "DRAFT", "CONFIRMED", "PARTIALLY_SHIPPED", "SHIPPED"];

export function SalesOrdersPage({ me }: { me: Me }) {
  const [status, setStatus] = useState<SalesOrderStatus | undefined>(undefined);
  const orders = useSalesOrders(status);
  const names = useNames();

  return (
    <>
      <PageHeader
        title="Sales orders"
        subtitle="Confirming an order reserves its stock, all lines or none; shipping consumes the reservation."
        actions={hasRole(me, "SALES") && (
          <Link to="/sales-orders/new" className="rounded-md bg-teal-700 px-3 py-1.5 text-sm font-medium text-white shadow-sm hover:bg-teal-800">
            New sales order
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
        {orders.data && orders.data.length === 0 && <EmptyState>No sales orders here yet.</EmptyState>}
        {orders.data && orders.data.length > 0 && (
          <TableWrap>
            <table className={tableClass}>
              <thead>
                <tr>
                  <th className={thClass}>Order</th>
                  <th className={thClass}>Customer</th>
                  <th className={thClass}>Ships from</th>
                  <th className={thClass}>Status</th>
                  <th className={thNumClass}>Net total</th>
                  <th className={thClass}>Taken</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {orders.data.map((o) => (
                  <tr key={o.id} className="hover:bg-slate-50">
                    <td className={tdClass}>
                      <Link to={`/sales-orders/${o.id}`} className="font-medium text-teal-800 hover:underline">{o.orderNumber}</Link>
                    </td>
                    <td className={tdClass}>{names.customer(o.customerId)}</td>
                    <td className={tdClass}>{names.warehouse(o.warehouseId)}</td>
                    <td className={tdClass}><StatusBadge status={o.status} /></td>
                    <td className={tdNumClass}>{o.netTotal.formatted} {o.currency}</td>
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
