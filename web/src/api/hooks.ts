import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiFetch, query } from "./client";
import type {
  Alert,
  AlertStatus,
  BinStock,
  Customer,
  GoodsReceipt,
  Invoice,
  Me,
  Movement,
  Product,
  PurchaseOrder,
  PurchaseOrderAction,
  PurchaseOrderStatus,
  Reconciliation,
  ReorderRule,
  SalesOrder,
  SalesOrderAction,
  SalesOrderStatus,
  Shipment,
  StockLevel,
  Supplier,
  Transfer,
  UnitOfMeasure,
  Warehouse,
} from "./types";

/**
 * All server data goes through these TanStack Query hooks: cached, de-duplicated, and refreshed
 * after every change. Query keys start with the resource name, so a mutation can invalidate
 * exactly what it touched.
 */

// ---- queries ------------------------------------------------------------------------------------

export const useMe = () => useQuery({ queryKey: ["me"], queryFn: () => apiFetch<Me>("/api/me"), retry: false });

export const useUnitsOfMeasure = () =>
  useQuery({ queryKey: ["units"], queryFn: () => apiFetch<UnitOfMeasure[]>("/api/units-of-measure"), staleTime: Infinity });

export const useProducts = () => useQuery({ queryKey: ["products"], queryFn: () => apiFetch<Product[]>("/api/products") });

export const useWarehouses = () =>
  useQuery({ queryKey: ["warehouses"], queryFn: () => apiFetch<Warehouse[]>("/api/warehouses") });

export const useSuppliers = () => useQuery({ queryKey: ["suppliers"], queryFn: () => apiFetch<Supplier[]>("/api/suppliers") });

export const useCustomers = () => useQuery({ queryKey: ["customers"], queryFn: () => apiFetch<Customer[]>("/api/customers") });

export const useStockLevels = (warehouseId?: number) =>
  useQuery({
    queryKey: ["stock", "levels", warehouseId ?? null],
    queryFn: () => apiFetch<StockLevel[]>(`/api/stock/levels${query({ warehouseId })}`),
  });

export const useBinStock = (warehouseId?: number, productId?: number) =>
  useQuery({
    queryKey: ["stock", "bins", warehouseId ?? null, productId ?? null],
    queryFn: () => apiFetch<BinStock[]>(`/api/stock/bins${query({ warehouseId, productId })}`),
  });

export const useMovements = (warehouseId: number | undefined, productId: number | undefined, enabled = true) =>
  useQuery({
    queryKey: ["stock", "movements", warehouseId ?? null, productId ?? null],
    queryFn: () => apiFetch<Movement[]>(`/api/stock/movements${query({ warehouseId, productId, limit: 200 })}`),
    enabled,
  });

/** Alerts are raised by event listeners a moment after stock changes, so poll while visible. */
export const useAlerts = (status?: AlertStatus) =>
  useQuery({
    queryKey: ["alerts", status ?? null],
    queryFn: () => apiFetch<Alert[]>(`/api/alerts${query({ status })}`),
    refetchInterval: 10_000,
  });

export const useReorderRules = () =>
  useQuery({ queryKey: ["reorder-rules"], queryFn: () => apiFetch<ReorderRule[]>("/api/reorder-rules") });

export const usePurchaseOrders = (status?: PurchaseOrderStatus) =>
  useQuery({
    queryKey: ["purchase-orders", "list", status ?? null],
    queryFn: () => apiFetch<PurchaseOrder[]>(`/api/purchase-orders${query({ status })}`),
  });

export const usePurchaseOrder = (id: number) =>
  useQuery({ queryKey: ["purchase-orders", id], queryFn: () => apiFetch<PurchaseOrder>(`/api/purchase-orders/${id}`) });

export const useGoodsReceipts = (purchaseOrderId: number) =>
  useQuery({
    queryKey: ["purchase-orders", purchaseOrderId, "receipts"],
    queryFn: () => apiFetch<GoodsReceipt[]>(`/api/purchase-orders/${purchaseOrderId}/receipts`),
  });

export const useSalesOrders = (status?: SalesOrderStatus) =>
  useQuery({
    queryKey: ["sales-orders", "list", status ?? null],
    queryFn: () => apiFetch<SalesOrder[]>(`/api/sales-orders${query({ status })}`),
  });

export const useSalesOrder = (id: number) =>
  useQuery({ queryKey: ["sales-orders", id], queryFn: () => apiFetch<SalesOrder>(`/api/sales-orders/${id}`) });

export const useShipments = (salesOrderId: number) =>
  useQuery({
    queryKey: ["sales-orders", salesOrderId, "shipments"],
    queryFn: () => apiFetch<Shipment[]>(`/api/sales-orders/${salesOrderId}/shipments`),
  });

/**
 * Invoices of one order (or the latest 100). Shipments are invoiced by an event listener just
 * after they commit, so with `expected` (the number of shipments) this polls until there are
 * that many invoices, then stops.
 */
export const useInvoices = (salesOrderId?: number, options: { enabled?: boolean; expected?: number } = {}) =>
  useQuery({
    queryKey: ["invoices", salesOrderId ?? null],
    queryFn: () => apiFetch<Invoice[]>(`/api/invoices${query({ salesOrderId })}`),
    enabled: options.enabled ?? true,
    refetchInterval: (q) => (options.expected !== undefined && (q.state.data?.length ?? 0) < options.expected ? 1_500 : false),
  });

export const useInvoice = (id: number) =>
  useQuery({ queryKey: ["invoices", "one", id], queryFn: () => apiFetch<Invoice>(`/api/invoices/${id}`) });

export const useTransfers = () => useQuery({ queryKey: ["transfers"], queryFn: () => apiFetch<Transfer[]>("/api/transfers") });

/** Runs only when asked (it recomputes everything from the ledger). */
export const useReconciliation = () =>
  useQuery({
    queryKey: ["reconciliation"],
    queryFn: () => apiFetch<Reconciliation>("/api/reports/reconciliation"),
    enabled: false,
  });

// ---- mutations ----------------------------------------------------------------------------------

/**
 * A POST/PUT, followed by a refresh of everything except "who am I". In an ERP one action
 * ripples (a shipment changes stock, alerts, the order and, a moment later, invoices), so
 * refreshing broadly is simpler and safer than listing each affected query.
 */
function useApiMutation<TVariables, TResult>(request: (variables: TVariables) => Promise<TResult>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: request,
    onSuccess: () => client.invalidateQueries({ predicate: (q) => q.queryKey[0] !== "me" && q.queryKey[0] !== "units" }),
  });
}

const post = <T,>(path: string, body?: unknown) => apiFetch<T>(path, { method: "POST", body });
const put = <T,>(path: string, body: unknown) => apiFetch<T>(path, { method: "PUT", body });

export type OrderLineInput = { productId: number; uom: string; quantity: number; unitPriceMinor: number };

export const useCreatePurchaseOrder = () =>
  useApiMutation((body: { supplierId: number; warehouseId: number; expectedDate?: string; notes?: string; lines: OrderLineInput[] }) =>
    post<PurchaseOrder>("/api/purchase-orders", body));

export const usePurchaseOrderAction = (id: number) =>
  useApiMutation(({ action, comment }: { action: PurchaseOrderAction; comment?: string }) =>
    post<PurchaseOrder>(`/api/purchase-orders/${id}/actions/${action.toLowerCase()}`, { comment }));

export const useReceiveGoods = (purchaseOrderId: number) =>
  useApiMutation((lines: { lineId: number; quantity: number; binId?: number }[]) =>
    post<GoodsReceipt>(`/api/purchase-orders/${purchaseOrderId}/receipts`, { lines }));

export const useCreateSalesOrder = () =>
  useApiMutation((body: { customerId: number; warehouseId: number; notes?: string; lines: OrderLineInput[] }) =>
    post<SalesOrder>("/api/sales-orders", body));

export const useSalesOrderAction = (id: number) =>
  useApiMutation(({ action, comment }: { action: SalesOrderAction; comment?: string }) =>
    post<SalesOrder>(`/api/sales-orders/${id}/actions/${action.toLowerCase()}`, { comment }));

export const useShip = (salesOrderId: number) =>
  useApiMutation((lines: { lineId: number; quantity: number }[]) =>
    post<Shipment>(`/api/sales-orders/${salesOrderId}/shipments`, { lines }));

export const useIssueInvoice = () => useApiMutation((shipmentId: number) => post<Invoice>(`/api/shipments/${shipmentId}/invoice`));

export const useCreateTransfer = () =>
  useApiMutation((body: {
    fromWarehouseId: number;
    toWarehouseId: number;
    lines: { productId: number; fromBinId: number; quantity: number; uom?: string }[];
  }) => post<Transfer>("/api/transfers", body));

export const useReceiveTransfer = () =>
  useApiMutation(({ id, toBinId }: { id: number; toBinId?: number }) => post<Transfer>(`/api/transfers/${id}/receive`, { toBinId }));

export const useAdjustStock = () =>
  useApiMutation((body: { warehouseId: number; binId: number; productId: number; quantity: number; uom?: string; reason: string }) =>
    post<unknown>("/api/stock/adjustments", body));

export const useAcknowledgeAlert = () => useApiMutation((id: number) => post<Alert>(`/api/alerts/${id}/acknowledge`));

export const useSetReorderRule = () =>
  useApiMutation(({ warehouseId, productId, ...rule }: { warehouseId: number; productId: number; reorderPoint: number; reorderQuantity: number }) =>
    put<ReorderRule>(`/api/reorder-rules/${warehouseId}/${productId}`, rule));

export const useCreateProduct = () =>
  useApiMutation((body: { sku: string; name: string; baseUom: string; taxRateBp: number; units: { uom: string; baseUnitsPerUom: number }[] }) =>
    post<Product>("/api/products", body));

export const useCreateWarehouse = () =>
  useApiMutation((body: { code: string; name: string }) => post<Warehouse>("/api/warehouses", body));

export const useAddBin = () =>
  useApiMutation(({ warehouseId, code }: { warehouseId: number; code: string }) => post<unknown>(`/api/warehouses/${warehouseId}/bins`, { code }));

export const useCreateSupplier = () =>
  useApiMutation((body: { code: string; name: string; email?: string; currency: string; paymentTermsDays: number }) =>
    post<Supplier>("/api/suppliers", body));

export const useCreateCustomer = () =>
  useApiMutation((body: {
    code: string;
    name: string;
    email?: string;
    addressLine1: string;
    addressLine2?: string;
    postalCode: string;
    city: string;
    countryCode: string;
    currency: string;
    paymentTermsDays: number;
  }) => post<Customer>("/api/customers", body));
