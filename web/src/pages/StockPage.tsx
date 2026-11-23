import { type FormEvent, useState } from "react";

import { ApiError } from "../api/client";
import { useAdjustStock, useBinStock, useMovements, useReorderRules, useStockLevels } from "../api/hooks";
import type { Me, StockLevel } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Button, Card, EmptyState, ErrorText, Field, inputClass, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass,
  thNumClass,
} from "../components/ui";
import { dateTime, hasRole, label, signed } from "../lib/format";

/**
 * Stock per warehouse and product, derived from the ledger: on hand, reserved for confirmed
 * orders, and available (on hand − reserved). Select a line to see its bins and its ledger.
 */
export function StockPage({ me }: { me: Me }) {
  const names = useNames();
  const [warehouseId, setWarehouseId] = useState("");
  const levels = useStockLevels(warehouseId ? Number(warehouseId) : undefined);
  const rules = useReorderRules();
  const [selected, setSelected] = useState<StockLevel | null>(null);

  const reorderPoint = (l: StockLevel) =>
    rules.data?.find((r) => r.warehouseId === l.warehouseId && r.productId === l.productId)?.reorderPoint;

  return (
    <>
      <PageHeader title="Stock" subtitle="Quantities in each product's base unit. Every number is the sum of the stock ledger." />
      <div className="space-y-4">
        <Card
          title="Balances"
          actions={(
            <select aria-label="Warehouse" className={`${inputClass} mt-0 w-56`} value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
              <option value="">All warehouses</option>
              {names.warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
            </select>
          )}
        >
          {levels.isPending && <Spinner />}
          <ErrorText error={levels.error} />
          {levels.data?.length === 0 && <EmptyState>No stock yet.</EmptyState>}
          {levels.data && levels.data.length > 0 && (
            <TableWrap>
              <table className={tableClass}>
                <thead>
                  <tr>
                    <th className={thClass}>Warehouse</th>
                    <th className={thClass}>Product</th>
                    <th className={thNumClass}>On hand</th>
                    <th className={thNumClass}>Reserved</th>
                    <th className={thNumClass}>Available</th>
                    <th className={thNumClass}>Reorder point</th>
                    <th className={thClass} />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {levels.data.map((l) => {
                    const point = reorderPoint(l);
                    const isSelected = selected?.warehouseId === l.warehouseId && selected.productId === l.productId;
                    return (
                      <tr
                        key={`${l.warehouseId}-${l.productId}`}
                        className={`cursor-pointer ${isSelected ? "bg-teal-50" : "hover:bg-slate-50"}`}
                        onClick={() => setSelected(l)}
                      >
                        <td className={tdClass}>{l.warehouseCode}</td>
                        <td className={tdClass}><span className="font-medium">{l.sku}</span> · {l.productName}</td>
                        <td className={tdNumClass}>{l.onHand} {l.baseUom}</td>
                        <td className={tdNumClass}>{l.reserved}</td>
                        <td className={`${tdNumClass} font-semibold`}>{l.available}</td>
                        <td className={tdNumClass}>{point ?? "—"}</td>
                        <td className={tdClass}>{point !== undefined && l.available <= point && <StatusBadge status="LOW" />}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </TableWrap>
          )}
        </Card>
        {selected && <LedgerCard level={selected} />}
        {hasRole(me, "MANAGER") && <AdjustmentForm />}
      </div>
    </>
  );
}

function LedgerCard({ level }: { level: StockLevel }) {
  const names = useNames();
  const bins = useBinStock(level.warehouseId, level.productId);
  const movements = useMovements(level.warehouseId, level.productId);
  return (
    <Card title={`${level.sku} in ${level.warehouseCode}: bins and ledger`}>
      <p className="mb-3 text-sm text-slate-600">
        In bins:{" "}
        {bins.data?.length ? bins.data.map((b) => `${b.binCode} ${b.onHand}`).join(" · ") : "none"}
      </p>
      {movements.isPending && <Spinner />}
      <TableWrap>
        <table className={tableClass}>
          <thead>
            <tr>
              <th className={thNumClass}>Seq</th>
              <th className={thClass}>When</th>
              <th className={thClass}>Movement</th>
              <th className={thClass}>Bin</th>
              <th className={thNumClass}>On hand</th>
              <th className={thNumClass}>Reserved</th>
              <th className={thNumClass}>On hand after</th>
              <th className={thNumClass}>Reserved after</th>
              <th className={thClass}>By</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {movements.data?.map((m) => (
              <tr key={m.id}>
                <td className={tdNumClass}>{m.levelSeq}</td>
                <td className={tdClass}>{dateTime(m.occurredAt)}</td>
                <td className={tdClass}>{label(m.type)}</td>
                <td className={tdClass}>{names.bin(m.binId)}</td>
                <td className={tdNumClass}>{m.onHandDelta ? signed(m.onHandDelta) : ""}</td>
                <td className={tdNumClass}>{m.reservedDelta ? signed(m.reservedDelta) : ""}</td>
                <td className={tdNumClass}>{m.onHandAfter}</td>
                <td className={tdNumClass}>{m.reservedAfter}</td>
                <td className={tdClass}>{m.recordedBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>
    </Card>
  );
}

/** A stock count correction. Negative removes stock, but never stock that is reserved. */
function AdjustmentForm() {
  const names = useNames();
  const adjust = useAdjustStock();
  const [warehouseId, setWarehouseId] = useState("");
  const [binId, setBinId] = useState("");
  const [productId, setProductId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const bins = names.warehouses?.find((w) => String(w.id) === warehouseId)?.bins ?? [];
  const errorFor = (field: string) => (adjust.error instanceof ApiError ? adjust.error.messageFor(field) : undefined);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amount = Number(quantity);
    if (!warehouseId || !binId || !productId || !reason.trim() || !Number.isInteger(amount) || amount === 0) {
      setProblem("Choose warehouse, bin and product, enter a whole non-zero quantity (negative to remove) and a reason.");
      return;
    }
    setProblem(null);
    adjust.mutate(
      { warehouseId: Number(warehouseId), binId: Number(binId), productId: Number(productId), quantity: amount, reason },
      { onSuccess: () => { setQuantity(""); setReason(""); } },
    );
  };

  return (
    <Card title="Adjust stock (count correction)">
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-6">
        <Field id="adj-warehouse" label="Warehouse">
          <select id="adj-warehouse" className={inputClass} value={warehouseId} onChange={(e) => { setWarehouseId(e.target.value); setBinId(""); }}>
            <option value="">Choose…</option>
            {names.warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
          </select>
        </Field>
        <Field id="adj-bin" label="Bin">
          <select id="adj-bin" className={inputClass} value={binId} onChange={(e) => setBinId(e.target.value)}>
            <option value="">Choose…</option>
            {bins.map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}
          </select>
        </Field>
        <Field id="adj-product" label="Product">
          <select id="adj-product" className={inputClass} value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">Choose…</option>
            {names.products?.map((p) => <option key={p.id} value={p.id}>{p.sku}</option>)}
          </select>
        </Field>
        <Field id="adj-qty" label="Change (base units)" error={errorFor("quantity")}>
          <input id="adj-qty" className={inputClass} inputMode="numeric" placeholder="e.g. -3" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </Field>
        <Field id="adj-reason" label="Reason">
          <input id="adj-reason" className={inputClass} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <div className="flex items-end">
          <Button type="submit" disabled={adjust.isPending}>Post adjustment</Button>
        </div>
      </form>
      <div className="mt-3 space-y-2">
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={adjust.error} names={names.product} />
      </div>
    </Card>
  );
}
