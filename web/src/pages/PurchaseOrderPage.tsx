import { type FormEvent, useState } from "react";
import { Link, useParams } from "react-router";

import { useGoodsReceipts, usePurchaseOrder, usePurchaseOrderAction, useReceiveGoods } from "../api/hooks";
import type { PurchaseOrder, PurchaseOrderAction } from "../api/types";
import { HistoryList } from "../components/HistoryList";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Button, Card, Definition, EmptyState, ErrorText, inputClass, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass,
  thClass, thNumClass,
} from "../components/ui";
import { dateTime, label, parseQuantity } from "../lib/format";

/** Buttons for the actions the server says this user may take now; destructive ones ask first. */
const ACTION_LOOK: Record<PurchaseOrderAction, { variant: "primary" | "secondary" | "danger"; confirm?: string }> = {
  SUBMIT: { variant: "primary" },
  APPROVE: { variant: "primary" },
  REJECT: { variant: "secondary", confirm: "Send this order back to its buyer as a draft?" },
  CANCEL: { variant: "danger", confirm: "Cancel this purchase order?" },
  CLOSE: { variant: "secondary", confirm: "Close the order? Nothing more will be received against it." },
};

export function PurchaseOrderPage() {
  const id = Number(useParams().id);
  const order = usePurchaseOrder(id);
  if (order.isPending) return <Spinner />;
  if (order.error) return <ErrorText error={order.error} />;
  return <PurchaseOrderView order={order.data} />;
}

export function PurchaseOrderView({ order }: { order: PurchaseOrder }) {
  const names = useNames();
  const action = usePurchaseOrderAction(order.id);
  const receipts = useGoodsReceipts(order.id);

  const run = (a: PurchaseOrderAction) => {
    const ask = ACTION_LOOK[a].confirm;
    if (ask && !window.confirm(ask)) return;
    action.mutate({ action: a });
  };

  return (
    <>
      <PageHeader
        title={<span className="flex items-center gap-3">{order.orderNumber} <StatusBadge status={order.status} /></span>}
        subtitle={<Link to="/purchase-orders" className="hover:underline">← All purchase orders</Link>}
        actions={order.allowedActions.map((a) => (
          <Button key={a} variant={ACTION_LOOK[a].variant} disabled={action.isPending} onClick={() => run(a)}>
            {label(a)}
          </Button>
        ))}
      />
      <div className="space-y-4">
        <ErrorText error={action.error} />
        <Card>
          <dl className="grid gap-4 sm:grid-cols-4">
            <Definition term="Supplier">{names.supplier(order.supplierId)}</Definition>
            <Definition term="Receive into">{names.warehouse(order.warehouseId)}</Definition>
            <Definition term="Total">{order.total.formatted} {order.currency}</Definition>
            <Definition term="Expected">{order.expectedDate ?? "—"}</Definition>
            <Definition term="Raised by">{order.createdBy} · {dateTime(order.createdAt)}</Definition>
            <Definition term="Approved by">{order.approvedBy ? `${order.approvedBy} · ${dateTime(order.approvedAt)}` : "—"}</Definition>
            <Definition term="Notes">{order.notes ?? "—"}</Definition>
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
                  <th className={thNumClass}>Received (base units)</th>
                  <th className={thNumClass}>Unit price</th>
                  <th className={thNumClass}>Line total</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {order.lines.map((l) => (
                  <tr key={l.id}>
                    <td className={tdClass}>{l.lineNo}</td>
                    <td className={tdClass}>{names.product(l.productId)}</td>
                    <td className={tdNumClass}>{l.quantity} {l.uom}</td>
                    <td className={tdNumClass}>{l.receivedBaseQuantity} / {l.baseQuantity} {names.baseUom(l.productId)}</td>
                    <td className={tdNumClass}>{l.unitPrice.formatted}</td>
                    <td className={tdNumClass}>{l.lineTotal.formatted}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        </Card>
        {order.canReceive && <ReceiveForm order={order} />}
        <Card title="Goods receipts">
          {receipts.data && receipts.data.length === 0 && <EmptyState>Nothing received yet.</EmptyState>}
          <ul className="divide-y divide-slate-100 text-sm">
            {receipts.data?.map((r) => (
              <li key={r.id} className="py-2">
                <span className="font-medium">{r.receiptNumber}</span> · {r.receivedBy} · {dateTime(r.receivedAt)}:{" "}
                {r.lines.map((l) => `${l.quantity} × ${names.sku(l.productId)} → ${names.bin(l.binId)}`).join("; ")}
              </li>
            ))}
          </ul>
        </Card>
        <Card title="History">
          <HistoryList entries={order.history} />
        </Card>
      </div>
    </>
  );
}

/** Book a delivery: a quantity per line (in the line's unit) and the bin it goes into. */
function ReceiveForm({ order }: { order: PurchaseOrder }) {
  const names = useNames();
  const receive = useReceiveGoods(order.id);
  const bins = names.warehouses?.find((w) => w.id === order.warehouseId)?.bins.filter((b) => b.active) ?? [];
  const receivingBin = bins.find((b) => b.receiving);
  const open = order.lines.filter((l) => l.receivedBaseQuantity < l.baseQuantity);
  const [quantities, setQuantities] = useState<Record<number, string>>({});
  const [binId, setBinId] = useState<string>("");
  const [problem, setProblem] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const lines: { lineId: number; quantity: number; binId?: number }[] = [];
    for (const line of open) {
      const text = quantities[line.id]?.trim();
      if (!text) continue;
      const quantity = parseQuantity(text);
      if (quantity === null) {
        setProblem(`Line ${line.lineNo}: enter a quantity above zero, with at most 3 decimals.`);
        return;
      }
      lines.push({ lineId: line.id, quantity, binId: binId ? Number(binId) : undefined });
    }
    if (lines.length === 0) {
      setProblem("Enter what arrived for at least one line.");
      return;
    }
    setProblem(null);
    receive.mutate(lines, { onSuccess: () => setQuantities({}) });
  };

  return (
    <Card title="Receive goods">
      <form onSubmit={submit} className="space-y-3">
        <TableWrap>
          <table className={tableClass}>
            <thead>
              <tr>
                <th className={thClass}>Line</th>
                <th className={thNumClass}>Still expected</th>
                <th className={thClass}>Arrived now</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {open.map((l) => (
                <tr key={l.id}>
                  <td className={tdClass}>{l.lineNo}. {names.product(l.productId)}</td>
                  <td className={tdNumClass}>{l.baseQuantity - l.receivedBaseQuantity} {names.baseUom(l.productId)}</td>
                  <td className={tdClass}>
                    <div className="flex items-center gap-2">
                      <input
                        aria-label={`Quantity received for line ${l.lineNo}`}
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
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="block text-xs font-medium uppercase tracking-wide text-slate-500">Into bin</span>
            <select className={`${inputClass} w-56`} value={binId} onChange={(e) => setBinId(e.target.value)}>
              <option value="">{receivingBin ? `${receivingBin.code} (receiving)` : "Receiving bin"}</option>
              {bins.filter((b) => !b.receiving).map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}
            </select>
          </label>
          <Button type="submit" disabled={receive.isPending}>{receive.isPending ? "Booking…" : "Book receipt"}</Button>
        </div>
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={receive.error} />
      </form>
    </Card>
  );
}
