import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";

import { ApiError } from "../api/client";
import { useCreateSalesOrder, useStockLevels } from "../api/hooks";
import { OrderLinesEditor } from "../components/OrderLinesEditor";
import { useNames } from "../components/useNames";
import { Button, Card, ErrorText, Field, inputClass, PageHeader } from "../components/ui";
import { type DraftLine, emptyLine, toOrderLines } from "../lib/orderLines";

/** Take a customer's order (a DRAFT); confirming it on the order page reserves the stock. */
export function NewSalesOrderPage() {
  const navigate = useNavigate();
  const names = useNames();
  const create = useCreateSalesOrder();
  const [customerId, setCustomerId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([emptyLine]);
  const [problem, setProblem] = useState<string | null>(null);
  const stock = useStockLevels(warehouseId ? Number(warehouseId) : undefined);

  const customer = names.customers?.find((c) => String(c.id) === customerId);
  const currency = customer?.currency ?? "EUR";
  const errorFor = (field: string) => (create.error instanceof ApiError ? create.error.messageFor(field) : undefined);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!customerId || !warehouseId) {
      setProblem("Choose a customer and a warehouse.");
      return;
    }
    const parsed = toOrderLines(lines, currency);
    if ("problem" in parsed) {
      setProblem(parsed.problem);
      return;
    }
    setProblem(null);
    create.mutate(
      { customerId: Number(customerId), warehouseId: Number(warehouseId), notes: notes || undefined, lines: parsed.lines },
      { onSuccess: (order) => void navigate(`/sales-orders/${order.id}`) },
    );
  };

  return (
    <>
      <PageHeader title="New sales order" subtitle="Saved as a draft: nothing is reserved until you confirm it." />
      <form onSubmit={submit} className="space-y-4">
        <Card title="Order">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field id="customer" label="Customer" error={errorFor("customerId")}>
              <select id="customer" className={inputClass} value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
                <option value="">Choose…</option>
                {names.customers?.filter((c) => c.active).map((c) => (
                  <option key={c.id} value={c.id}>{c.name} ({c.currency})</option>
                ))}
              </select>
            </Field>
            <Field id="warehouse" label="Ship from" error={errorFor("warehouseId")}>
              <select id="warehouse" className={inputClass} value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                <option value="">Choose…</option>
                {names.warehouses?.filter((w) => w.active).map((w) => (
                  <option key={w.id} value={w.id}>{w.code} · {w.name}</option>
                ))}
              </select>
            </Field>
            <Field id="notes" label="Notes">
              <input id="notes" className={inputClass} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
            </Field>
          </div>
          {warehouseId && stock.data && (
            <p className="mt-3 text-xs text-slate-500">
              Available here now:{" "}
              {stock.data.filter((s) => s.available > 0).map((s) => `${s.sku} ${s.available} ${s.baseUom}`).join(" · ") || "nothing"}
            </p>
          )}
        </Card>
        <Card title="Lines">
          <OrderLinesEditor lines={lines} onChange={setLines} products={names.products ?? []} currency={currency} errorFor={errorFor} />
        </Card>
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={create.error} />
        <div className="flex gap-2">
          <Button type="submit" disabled={create.isPending}>{create.isPending ? "Saving…" : "Save draft"}</Button>
          <Button variant="secondary" onClick={() => void navigate("/sales-orders")}>Cancel</Button>
        </div>
      </form>
    </>
  );
}
