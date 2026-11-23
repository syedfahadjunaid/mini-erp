import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";

import { ApiError } from "../api/client";
import { useCreatePurchaseOrder } from "../api/hooks";
import { OrderLinesEditor } from "../components/OrderLinesEditor";
import { useNames } from "../components/useNames";
import { Button, Card, ErrorText, Field, inputClass, PageHeader } from "../components/ui";
import { type DraftLine, emptyLine, toOrderLines } from "../lib/orderLines";

/** Raise a purchase order (a DRAFT); submit it for approval from the order page. */
export function NewPurchaseOrderPage() {
  const navigate = useNavigate();
  const names = useNames();
  const create = useCreatePurchaseOrder();
  const [supplierId, setSupplierId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [expectedDate, setExpectedDate] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([emptyLine]);
  const [problem, setProblem] = useState<string | null>(null);

  const supplier = names.suppliers?.find((s) => String(s.id) === supplierId);
  const currency = supplier?.currency ?? "EUR";
  const errorFor = (field: string) => (create.error instanceof ApiError ? create.error.messageFor(field) : undefined);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!supplierId || !warehouseId) {
      setProblem("Choose a supplier and a warehouse.");
      return;
    }
    const parsed = toOrderLines(lines, currency);
    if ("problem" in parsed) {
      setProblem(parsed.problem);
      return;
    }
    setProblem(null);
    create.mutate(
      {
        supplierId: Number(supplierId),
        warehouseId: Number(warehouseId),
        expectedDate: expectedDate || undefined,
        notes: notes || undefined,
        lines: parsed.lines,
      },
      { onSuccess: (order) => void navigate(`/purchase-orders/${order.id}`) },
    );
  };

  return (
    <>
      <PageHeader title="New purchase order" subtitle="Saved as a draft. Prices are per unit, in the supplier's currency." />
      <form onSubmit={submit} className="space-y-4">
        <Card title="Order">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field id="supplier" label="Supplier" error={errorFor("supplierId")}>
              <select id="supplier" className={inputClass} value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
                <option value="">Choose…</option>
                {names.suppliers?.filter((s) => s.active).map((s) => (
                  <option key={s.id} value={s.id}>{s.name} ({s.currency})</option>
                ))}
              </select>
            </Field>
            <Field id="warehouse" label="Receive into" error={errorFor("warehouseId")}>
              <select id="warehouse" className={inputClass} value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                <option value="">Choose…</option>
                {names.warehouses?.filter((w) => w.active).map((w) => (
                  <option key={w.id} value={w.id}>{w.code} · {w.name}</option>
                ))}
              </select>
            </Field>
            <Field id="expected" label="Expected on">
              <input id="expected" type="date" className={inputClass} value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} />
            </Field>
          </div>
          <div className="mt-4">
            <Field id="notes" label="Notes">
              <input id="notes" className={inputClass} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
            </Field>
          </div>
        </Card>
        <Card title="Lines">
          <OrderLinesEditor lines={lines} onChange={setLines} products={names.products ?? []} currency={currency} errorFor={errorFor} />
        </Card>
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={create.error} />
        <div className="flex gap-2">
          <Button type="submit" disabled={create.isPending}>{create.isPending ? "Saving…" : "Save draft"}</Button>
          <Button variant="secondary" onClick={() => void navigate("/purchase-orders")}>Cancel</Button>
        </div>
      </form>
    </>
  );
}
