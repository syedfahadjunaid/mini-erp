import { Route, Routes } from "react-router";

import type { Me } from "./api/types";
import { Layout } from "./components/Layout";
import { DashboardPage } from "./pages/DashboardPage";
import { InvoicesPage } from "./pages/InvoicesPage";
import { MasterDataPage } from "./pages/MasterDataPage";
import { NewPurchaseOrderPage } from "./pages/NewPurchaseOrderPage";
import { NewSalesOrderPage } from "./pages/NewSalesOrderPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { PurchaseOrderPage } from "./pages/PurchaseOrderPage";
import { PurchaseOrdersPage } from "./pages/PurchaseOrdersPage";
import { ReportsPage } from "./pages/ReportsPage";
import { SalesOrderPage } from "./pages/SalesOrderPage";
import { SalesOrdersPage } from "./pages/SalesOrdersPage";
import { StockPage } from "./pages/StockPage";
import { TransfersPage } from "./pages/TransfersPage";

/** The signed-in app. Rendered only once /api/me succeeded (see AuthGate). */
export function App({ me }: { me: Me }) {
  return (
    <Routes>
      <Route element={<Layout me={me} />}>
        <Route index element={<DashboardPage me={me} />} />
        <Route path="stock" element={<StockPage me={me} />} />
        <Route path="purchase-orders" element={<PurchaseOrdersPage me={me} />} />
        <Route path="purchase-orders/new" element={<NewPurchaseOrderPage />} />
        <Route path="purchase-orders/:id" element={<PurchaseOrderPage />} />
        <Route path="sales-orders" element={<SalesOrdersPage me={me} />} />
        <Route path="sales-orders/new" element={<NewSalesOrderPage />} />
        <Route path="sales-orders/:id" element={<SalesOrderPage me={me} />} />
        <Route path="transfers" element={<TransfersPage me={me} />} />
        <Route path="invoices" element={<InvoicesPage me={me} />} />
        <Route path="reports" element={<ReportsPage me={me} />} />
        <Route path="master-data" element={<MasterDataPage me={me} />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
