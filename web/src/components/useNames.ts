import { useCallback } from "react";

import { useCustomers, useProducts, useSuppliers, useWarehouses } from "../api/hooks";

/**
 * Documents reference products, warehouses and partners by id. This turns ids into readable
 * names from the (cached) master data lists, falling back to "#id" while they load.
 */
export function useNames() {
  const products = useProducts().data;
  const warehouses = useWarehouses().data;
  const suppliers = useSuppliers().data;
  const customers = useCustomers().data;

  const product = useCallback(
    (id: number) => {
      const p = products?.find((x) => x.id === id);
      return p ? `${p.sku} · ${p.name}` : `#${id}`;
    },
    [products],
  );
  const sku = useCallback((id: number) => products?.find((x) => x.id === id)?.sku ?? `#${id}`, [products]);
  const baseUom = useCallback((id: number) => products?.find((x) => x.id === id)?.baseUom ?? "", [products]);
  const warehouse = useCallback((id: number) => warehouses?.find((x) => x.id === id)?.code ?? `#${id}`, [warehouses]);
  const bin = useCallback(
    (id: number | null) => {
      if (id === null) return "—";
      for (const w of warehouses ?? []) {
        const b = w.bins.find((x) => x.id === id);
        if (b) return b.code;
      }
      return `#${id}`;
    },
    [warehouses],
  );
  const supplier = useCallback((id: number) => suppliers?.find((x) => x.id === id)?.name ?? `#${id}`, [suppliers]);
  const customer = useCallback((id: number) => customers?.find((x) => x.id === id)?.name ?? `#${id}`, [customers]);

  return { product, sku, baseUom, warehouse, bin, supplier, customer, products, warehouses, suppliers, customers };
}
