import type { OrderLineInput } from "../api/hooks";
import { parseMoney, parseQuantity } from "./format";

/** One editable order line as the user types it (text, validated on submit). */
export type DraftLine = { productId: string; uom: string; quantity: string; price: string };

export const emptyLine: DraftLine = { productId: "", uom: "", quantity: "", price: "" };

/** Turns the typed lines into the API's shape, or explains the first problem. */
export function toOrderLines(lines: DraftLine[], currency: string): { lines: OrderLineInput[] } | { problem: string } {
  const result: OrderLineInput[] = [];
  for (const [index, line] of lines.entries()) {
    const n = index + 1;
    if (!line.productId) return { problem: `Line ${n}: choose a product.` };
    const quantity = parseQuantity(line.quantity);
    if (quantity === null) return { problem: `Line ${n}: enter a quantity above zero, with at most 3 decimals.` };
    const price = parseMoney(line.price, currency);
    if (price === null) return { problem: `Line ${n}: enter a price like 19.99.` };
    result.push({ productId: Number(line.productId), uom: line.uom, quantity, unitPriceMinor: price });
  }
  return { lines: result };
}
