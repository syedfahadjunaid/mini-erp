import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { PurchaseOrder } from "../api/types";
import { PurchaseOrderView } from "./PurchaseOrderPage";

const money = (amountMinor: number) => ({ amountMinor, currency: "EUR", formatted: (amountMinor / 100).toFixed(2) });

function order(overrides: Partial<PurchaseOrder> = {}): PurchaseOrder {
  return {
    id: 12,
    orderNumber: "PO-000012",
    supplierId: 1,
    warehouseId: 2,
    currency: "EUR",
    status: "SUBMITTED",
    expectedDate: null,
    notes: null,
    createdBy: "pam",
    createdAt: "2026-11-23T10:00:00Z",
    approvedBy: null,
    approvedAt: null,
    version: 1,
    total: money(45_000),
    lines: [{
      id: 99, lineNo: 1, productId: 5, uom: "BOX", quantity: 10, baseQuantity: 120, receivedBaseQuantity: 0,
      unitPrice: money(4500), lineTotal: money(45_000),
    }],
    allowedActions: ["APPROVE", "REJECT"],
    canEdit: false,
    canReceive: false,
    history: [{ action: "SUBMIT", fromStatus: "DRAFT", toStatus: "SUBMITTED", actor: "pam", occurredAt: "2026-11-23T10:01:00Z", comment: null }],
    ...overrides,
  };
}

function renderView(po: PurchaseOrder) {
  // Lookups (products, warehouses, ...) and receipts aren't needed: their queries stay disabled.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<PurchaseOrderView order={po} />, { wrapper });
}

describe("PurchaseOrderView", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("offers exactly the actions the server allows this user", () => {
    renderView(order());
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
    expect(screen.queryByText("Receive goods")).not.toBeInTheDocument();
  });

  it("shows no buttons at all when the server allows nothing (e.g. the buyer looking at their own order)", () => {
    renderView(order({ allowedActions: [] }));
    expect(screen.queryAllByRole("button", { name: /approve|reject|submit|cancel|close/i })).toHaveLength(0);
  });

  it("sends the chosen action to the API", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(order({ status: "APPROVED", allowedActions: [] })), {
      status: 200, headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    renderView(order());

    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(fetchMock).toHaveBeenCalledWith("/api/purchase-orders/12/actions/approve", expect.objectContaining({ method: "POST" }));
  });

  it("shows the receipt form only when the server says this user may receive", () => {
    renderView(order({ status: "APPROVED", allowedActions: ["CANCEL"], canReceive: true }));
    expect(screen.getByText("Receive goods")).toBeInTheDocument();
    expect(screen.getByLabelText("Quantity received for line 1")).toBeInTheDocument();
  });
});
