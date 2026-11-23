/** Shapes of the API's JSON, mirroring the Java records (…Dtos classes) in services/erp-api. */

export type Role = "ADMIN" | "PURCHASING" | "MANAGER" | "WAREHOUSE" | "SALES" | "FINANCE";

export type Me = { username: string; displayName: string; roles: Role[] };

/** Integer minor units + currency; `formatted` is for display only (e.g. "12.50"). */
export type Money = { amountMinor: number; currency: string; formatted: string };

export type HistoryEntry = {
  action: string;
  fromStatus: string | null;
  toStatus: string;
  actor: string;
  occurredAt: string;
  comment: string | null;
};

// ---- master data -------------------------------------------------------------------------------

export type UnitOfMeasure = { code: string; name: string };
export type ProductUnit = { uom: string; baseUnitsPerUom: number };
export type Product = {
  id: number;
  sku: string;
  name: string;
  baseUom: string;
  taxRateBp: number;
  active: boolean;
  units: ProductUnit[];
};

export type Bin = { id: number; warehouseId: number; code: string; receiving: boolean; active: boolean };
export type Warehouse = { id: number; code: string; name: string; active: boolean; bins: Bin[] };

export type Supplier = {
  id: number;
  code: string;
  name: string;
  email: string | null;
  currency: string;
  paymentTermsDays: number;
  active: boolean;
};

export type Address = { line1: string; line2: string | null; postalCode: string; city: string; countryCode: string };
export type Customer = {
  id: number;
  code: string;
  name: string;
  email: string | null;
  address: Address;
  currency: string;
  paymentTermsDays: number;
  active: boolean;
};

// ---- stock ------------------------------------------------------------------------------------

/** Quantities are in the product's base unit. available = onHand − reserved. */
export type StockLevel = {
  warehouseId: number;
  warehouseCode: string;
  productId: number;
  sku: string;
  productName: string;
  baseUom: string;
  onHand: number;
  reserved: number;
  available: number;
  updatedAt: string;
};

export type BinStock = { warehouseId: number; binId: number; binCode: string; productId: number; sku: string; onHand: number };

export type MovementType = "RECEIPT" | "RESERVE" | "RELEASE" | "SHIPMENT" | "TRANSFER_OUT" | "TRANSFER_IN" | "ADJUSTMENT";

export type Movement = {
  id: number;
  type: MovementType;
  warehouseId: number;
  binId: number | null;
  productId: number;
  sku: string;
  onHandDelta: number;
  reservedDelta: number;
  levelSeq: number;
  onHandAfter: number;
  reservedAfter: number;
  sourceType: string;
  sourceId: number;
  occurredAt: string;
  recordedBy: string;
};

export type TransferStatus = "IN_TRANSIT" | "RECEIVED";
export type Transfer = {
  id: number;
  transferNumber: string;
  fromWarehouseId: number;
  toWarehouseId: number;
  status: TransferStatus;
  createdBy: string;
  createdAt: string;
  receivedBy: string | null;
  receivedAt: string | null;
  lines: { id: number; lineNo: number; productId: number; quantity: number; fromBinId: number; toBinId: number | null }[];
};

export type AlertStatus = "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
export type Alert = {
  id: number;
  warehouseId: number;
  warehouseCode: string;
  productId: number;
  sku: string;
  productName: string;
  status: AlertStatus;
  reorderPoint: number;
  reorderQuantity: number;
  availableAtOpen: number;
  availableNow: number;
  raisedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
};

export type ReorderRule = {
  warehouseId: number;
  warehouseCode: string;
  productId: number;
  sku: string;
  reorderPoint: number;
  reorderQuantity: number;
  available: number;
  updatedBy: string;
  updatedAt: string;
};

// ---- purchasing -------------------------------------------------------------------------------

export type PurchaseOrderStatus =
  | "DRAFT" | "SUBMITTED" | "APPROVED" | "PARTIALLY_RECEIVED" | "RECEIVED" | "CLOSED" | "CANCELLED";
export type PurchaseOrderAction = "SUBMIT" | "APPROVE" | "REJECT" | "CANCEL" | "CLOSE";

export type PurchaseOrderLine = {
  id: number;
  lineNo: number;
  productId: number;
  uom: string;
  quantity: number;
  baseQuantity: number;
  receivedBaseQuantity: number;
  unitPrice: Money;
  lineTotal: Money;
};

export type PurchaseOrder = {
  id: number;
  orderNumber: string;
  supplierId: number;
  warehouseId: number;
  currency: string;
  status: PurchaseOrderStatus;
  expectedDate: string | null;
  notes: string | null;
  createdBy: string;
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
  version: number;
  total: Money;
  lines: PurchaseOrderLine[];
  /** What the signed-in user may do now. The UI shows exactly these, no more. */
  allowedActions: PurchaseOrderAction[];
  canEdit: boolean;
  canReceive: boolean;
  history: HistoryEntry[];
};

export type GoodsReceipt = {
  id: number;
  receiptNumber: string;
  purchaseOrderId: number;
  warehouseId: number;
  receivedBy: string;
  receivedAt: string;
  lines: { id: number; purchaseOrderLineId: number; productId: number; binId: number; quantity: number; baseQuantity: number }[];
  orderStatus: PurchaseOrderStatus;
};

// ---- sales ------------------------------------------------------------------------------------

export type SalesOrderStatus = "DRAFT" | "CONFIRMED" | "PARTIALLY_SHIPPED" | "SHIPPED" | "CLOSED" | "CANCELLED";
export type SalesOrderAction = "CONFIRM" | "CANCEL" | "CLOSE";

export type SalesOrderLine = {
  id: number;
  lineNo: number;
  productId: number;
  uom: string;
  quantity: number;
  baseQuantity: number;
  shippedBaseQuantity: number;
  openBaseQuantity: number;
  unitPrice: Money;
  taxRateBp: number;
  lineNet: Money;
};

export type SalesOrder = {
  id: number;
  orderNumber: string;
  customerId: number;
  warehouseId: number;
  currency: string;
  status: SalesOrderStatus;
  notes: string | null;
  createdBy: string;
  createdAt: string;
  confirmedBy: string | null;
  confirmedAt: string | null;
  version: number;
  netTotal: Money;
  lines: SalesOrderLine[];
  allowedActions: SalesOrderAction[];
  canEdit: boolean;
  canShip: boolean;
  history: HistoryEntry[];
};

export type Shipment = {
  id: number;
  shipmentNumber: string;
  salesOrderId: number;
  warehouseId: number;
  shippedBy: string;
  shippedAt: string;
  lines: {
    id: number;
    salesOrderLineId: number;
    productId: number;
    quantity: number;
    baseQuantity: number;
    picks: { binId: number; quantity: number }[];
  }[];
};

// ---- invoicing and reports --------------------------------------------------------------------

export type Invoice = {
  id: number;
  invoiceNumber: string;
  fiscalYear: number;
  sequenceNumber: number;
  shipmentId: number;
  salesOrderId: number;
  customerId: number;
  currency: string;
  issueDate: string;
  dueDate: string;
  billToName: string;
  billToAddress: string;
  netTotal: Money;
  taxTotal: Money;
  grossTotal: Money;
  issuedBy: string;
  issuedAt: string;
  lines: {
    lineNo: number;
    shipmentLineId: number;
    productId: number;
    description: string;
    uom: string;
    quantity: number;
    unitPrice: Money;
    taxRateBp: number;
    net: Money;
    tax: Money;
  }[];
};

export type Reconciliation = {
  balanced: boolean;
  checkedAt: string;
  movementsChecked: number;
  discrepancies: { check: string; warehouseId: number | null; productId: number | null; reference: string; expected: number; actual: number }[];
  inTransit: { productId: number; quantity: number }[];
};

/** One product short of stock, as a 422 "insufficient_stock" problem reports it. */
export type Shortage = { warehouseId: number; binId: number | null; productId: number; available: number; requested: number };
