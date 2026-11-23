import { type FormEvent, useState } from "react";
import { Link, useParams } from "react-router";

import { useInvoices, useSalesOrder, useSalesOrderAction, useShip, useShipments } from "../api/hooks";
import type { Me, SalesOrder, SalesOrderAction } from "../api/types";
import { HistoryList } from "../components/HistoryList";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Button, Card, Definition, DownloadLink, EmptyState, ErrorText, inputClass, PageHeader, Spinner, TableWrap, tableClass, tdClass,
  tdNumClass, thClass, thNumClass,
} from "../components/ui";
import { dateTime, hasRole, label, parseQuantity, percent } from "../lib/format";

const ACTION_LOOK: Record<SalesOrderAction, { variant: "primary" | "secondary" | "danger"; confirm?: string }> = {
  CONFIRM: { variant: "primary" },
  CANCEL: { variant: "danger", confirm: "Cancel this order? Any reserved stock is released." },
  CLOSE: { variant: "secondary", confirm: "Close the order? The rest won't ship and its reservation is released." },
};

export function SalesOrderPage({ me }: { me: Me }) {
  const id = Number(useParams().id);
  const order = useSalesOrder(id);
  if (order.isPending) return <Spinner />;
  if (order.error) return <ErrorText error={order.error} />;
  return <SalesOrderView order={order.data} me={me} />;
}

function SalesOrderView({ order, me }: { order: SalesOrder; me: Me }) {
  const names = useNames();
  const action = useSalesOrderAction(order.id);
  const shipments = useShipments(order.id);
  // Invoices are issued by an event listener a moment after each shipment: poll until each has one.
  const canSeeInvoices = hasRole(me, "FINANCE", "SALES", "MANAGER");
  const invoicesQuery = useInvoices(order.id, { enabled: canSeeInvoices, expected: shipments.data?.length ?? 0 });
  const invoices = invoicesQuery.data ?? [];

  const run = (a: SalesOrderAction) => {
    const ask = ACTION_LOOK[a].confirm;
    if (ask && !window.confirm(ask)) return;
    action.mutate({ action: a });
  };

  return (
    <>
      <PageHeader
        title={<span className="flex items-center gap-3">{order.orderNumber} <StatusBadge status={order.status} /></span>}
        subtitle={<Link to="/sales-orders" className="hover:underline">← All sales orders</Link>}
        actions={order.allowedActions.map((a) => (
          <Button key={a} variant={ACTION_LOOK[a].variant} disabled={action.isPending} onClick={() => run(a)}>
            {a === "CONFIRM" ? "Confirm and reserve stock" : label(a)}
          </Button>
        ))}
      />
      <div className="space-y-4">
        <ErrorText error={action.error} names={names.product} />
        <Card>
          <dl className="grid gap-4 sm:grid-cols-4">
            <Definition term="Customer">{names.customer(order.customerId)}</Definition>
            <Definition term="Ships from">{names.warehouse(order.warehouseId)}</Definition>
            <Definition term="Net total">{order.netTotal.formatted} {order.currency}</Definition>
            <Definition term="Notes">{order.notes ?? "—"}</Definition>
            <Definition term="Taken by">{order.createdBy} · {dateTime(order.createdAt)}</Definition>
            <Definition term="Confirmed by">{order.confirmedBy ? `${order.confirmedBy} · ${dateTime(order.confirmedAt)}` : "—"}</Definition>
          </dl>
        </Card>
        <Card title="Lines">
          <TableWrap>
            <table className={tableClass}>
              <thead>
                <tr>
                  <th className={thClass}>#</th>
                  <th className={thClass}>Product</th>
                  <th className={thNumClass}>Ordered</th>
                  <th className={thNumClass}>Shipped / open (base units)</th>
                  <th className={thNumClass}>Unit price</th>
                  <th className={thNumClass}>Tax</th>
                  <th className={thNumClass}>Net</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {order.lines.map((l) => (
                  <tr key={l.id}>
                    <td className={tdClass}>{l.lineNo}</td>
                    <td className={tdClass}>{names.product(l.productId)}</td>
                    <td className={tdNumClass}>{l.quantity} {l.uom}</td>
                    <td className={tdNumClass}>{l.shippedBaseQuantity} / {l.openBaseQuantity} {names.baseUom(l.productId)}</td>
                    <td className={tdNumClass}>{l.unitPrice.formatted}</td>
                    <td className={tdNumClass}>{percent(l.taxRateBp)}</td>
                    <td className={tdNumClass}>{l.lineNet.formatted}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        </Card>
        {order.canShip && <ShipForm order={order} />}
        <Card title="Shipments and invoices">
          {shipments.data && shipments.data.length === 0 && <EmptyState>Nothing shipped yet.</EmptyState>}
          <ul className="divide-y divide-slate-100 text-sm">
            {shipments.data?.map((s) => {
              const invoice = invoices.find((i) => i.shipmentId === s.id);
              return (
                <li key={s.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <span>
                    <span className="font-medium">{s.shipmentNumber}</span> · {s.shippedBy} · {dateTime(s.shippedAt)}:{" "}
                    {s.lines.map((l) => `${l.quantity} × ${names.sku(l.productId)} from ${l.picks.map((p) => names.bin(p.binId)).join(", ") || "—"}`).join("; ")}
                  </span>
                  {canSeeInvoices && (invoice ? (
                    <span className="flex items-center gap-2">
                      <span>{invoice.invoiceNumber} · {invoice.grossTotal.formatted} {invoice.currency}</span>
                      <DownloadLink href={`/api/invoices/${invoice.id}/pdf`}>PDF</DownloadLink>
                    </span>
                  ) : (
                    <span className="text-slate-500">Invoice being issued…</span>
                  ))}
                </li>
              );
            })}
          </ul>
        </Card>
        <Card title="History">
          <HistoryList entries={order.history} />
        </Card>
      </div>
    </>
  );
}

/** Ship some or all of what's open; the server picks the bins and records each pick in the ledger. */
function ShipForm({ order }: { order: SalesOrder }) {
  const names = useNames();
  const ship = useShip(order.id);
  const open = order.lines.filter((l) => l.openBaseQuantity > 0);
  const [quantities, setQuantities] = useState<Record<number, string>>({});
  const [problem, setProblem] = useState<string | null>(null);

  const fillAll = () => setQuantities(Object.fromEntries(open.map((l) => [l.id, String(openInLineUnit(l))])));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const lines: { lineId: number; quantity: number }[] = [];
    for (const line of open) {
      const text = quantities[line.id]?.trim();
      if (!text) continue;
      const quantity = parseQuantity(text);
      if (quantity === null) {
        setProblem(`Line ${line.lineNo}: enter a quantity above zero, with at most 3 decimals.`);
        return;
      }
      lines.push({ lineId: line.id, quantity });
    }
    if (lines.length === 0) {
      setProblem("Enter what you're shipping for at least one line.");
      return;
    }
    setProblem(null);
    ship.mutate(lines, { onSuccess: () => setQuantities({}) });
  };

  return (
    <Card title="Ship" actions={<Button variant="secondary" onClick={fillAll}>Fill in everything open</Button>}>
      <form onSubmit={submit} className="space-y-3">
        <TableWrap>
          <table className={tableClass}>
            <thead>
              <tr>
                <th className={thClass}>Line</th>
                <th className={thNumClass}>Open</th>
                <th className={thClass}>Ship now</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {open.map((l) => (
                <tr key={l.id}>
                  <td className={tdClass}>{l.lineNo}. {names.product(l.productId)}</td>
                  <td className={tdNumClass}>{openInLineUnit(l)} {l.uom}</td>
                  <td className={tdClass}>
                    <div className="flex items-center gap-2">
                      <input
                        aria-label={`Quantity to ship for line ${l.lineNo}`}
                        className={`${inputClass} mt-0 w-28`}
                        inputMode="decimal"
                        value={quantities[l.id] ?? ""}
                        onChange={(e) => setQuantities({ ...quantities, [l.id]: e.target.value })}
                      />
                      <span className="text-slate-500">{l.uom}</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>
        <Button type="submit" disabled={ship.isPending}>{ship.isPending ? "Shipping…" : "Ship"}</Button>
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={ship.error} names={names.product} />
      </form>
    </Card>
  );
}

/** The open quantity in the line's own unit (e.g. 1.5 BOX), for display and "fill in". */
function openInLineUnit(line: SalesOrder["lines"][number]): number {
  const perUnit = line.baseQuantity / line.quantity;
  return Math.round((line.openBaseQuantity / perUnit) * 1000) / 1000;
}
