import type { Product } from "../api/types";
import { type DraftLine, emptyLine } from "../lib/orderLines";
import { Button, Field, inputClass } from "./ui";

/**
 * The lines of a new purchase or sales order: product, unit (one of the product's units),
 * quantity and price per unit. Prices are typed as decimals ("19.99") and converted exactly to
 * minor units; server-side errors for a line show next to its fields.
 */
export function OrderLinesEditor({ lines, onChange, products, currency, errorFor }: {
  lines: DraftLine[];
  onChange: (lines: DraftLine[]) => void;
  products: Product[];
  currency: string;
  errorFor: (field: string) => string | undefined;
}) {
  const update = (index: number, patch: Partial<DraftLine>) =>
    onChange(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  return (
    <div className="space-y-3">
      {lines.map((line, index) => {
        const product = products.find((p) => String(p.id) === line.productId);
        return (
          <div key={index} className="grid grid-cols-2 gap-3 rounded-md bg-slate-50 p-3 sm:grid-cols-[3fr_1fr_1fr_1fr_auto]">
            <Field id={`line-${index}-product`} label={`Line ${index + 1} product`} error={errorFor(`lines[${index}].productId`)}>
              <select
                id={`line-${index}-product`}
                className={inputClass}
                value={line.productId}
                onChange={(e) => {
                  const chosen = products.find((p) => String(p.id) === e.target.value);
                  update(index, { productId: e.target.value, uom: chosen?.baseUom ?? "" });
                }}
              >
                <option value="">Choose a product…</option>
                {products.filter((p) => p.active).map((p) => (
                  <option key={p.id} value={p.id}>{p.sku} · {p.name}</option>
                ))}
              </select>
            </Field>
            <Field id={`line-${index}-uom`} label="Unit">
              <select
                id={`line-${index}-uom`}
                className={inputClass}
                value={line.uom}
                disabled={!product}
                onChange={(e) => update(index, { uom: e.target.value })}
              >
                {(product?.units ?? []).map((u) => (
                  <option key={u.uom} value={u.uom}>
                    {u.uom}{u.baseUnitsPerUom > 1 ? ` (${u.baseUnitsPerUom} ${product?.baseUom})` : ""}
                  </option>
                ))}
              </select>
            </Field>
            <Field id={`line-${index}-qty`} label="Quantity" error={errorFor(`lines[${index}].quantity`)}>
              <input
                id={`line-${index}-qty`}
                className={inputClass}
                inputMode="decimal"
                value={line.quantity}
                onChange={(e) => update(index, { quantity: e.target.value })}
              />
            </Field>
            <Field id={`line-${index}-price`} label={`Price (${currency})`} error={errorFor(`lines[${index}].unitPriceMinor`)}>
              <input
                id={`line-${index}-price`}
                className={inputClass}
                inputMode="decimal"
                placeholder="0.00"
                value={line.price}
                onChange={(e) => update(index, { price: e.target.value })}
              />
            </Field>
            <div className="flex items-end">
              <Button
                variant="secondary"
                aria-label={`Remove line ${index + 1}`}
                disabled={lines.length === 1}
                onClick={() => onChange(lines.filter((_, i) => i !== index))}
              >
                Remove
              </Button>
            </div>
          </div>
        );
      })}
      <Button variant="secondary" onClick={() => onChange([...lines, emptyLine])}>Add line</Button>
    </div>
  );
}
