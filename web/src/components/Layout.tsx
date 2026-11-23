import { NavLink, Outlet } from "react-router";

import type { Me, Role } from "../api/types";
import { signOut } from "../auth/session";
import { hasRole } from "../lib/format";

type NavItem = { to: string; label: string; roles?: Role[] };

/** Menu entries; `roles` hides entries a user can't use (cosmetic: the server enforces every role). */
const NAV: NavItem[] = [
  { to: "/", label: "Dashboard" },
  { to: "/stock", label: "Stock" },
  { to: "/purchase-orders", label: "Purchasing" },
  { to: "/sales-orders", label: "Sales" },
  { to: "/transfers", label: "Transfers" },
  { to: "/invoices", label: "Invoices", roles: ["FINANCE", "SALES", "MANAGER"] },
  { to: "/reports", label: "Reports", roles: ["WAREHOUSE", "MANAGER", "FINANCE"] },
  { to: "/master-data", label: "Master data" },
];

export function Layout({ me }: { me: Me }) {
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? "bg-teal-900 text-white" : "text-teal-50 hover:bg-teal-600"}`;
  return (
    <div className="min-h-screen">
      <header className="bg-teal-700 text-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2">
          <span className="text-base font-semibold tracking-tight">Mini ERP</span>
          <nav className="flex flex-wrap gap-1" aria-label="Main">
            {NAV.filter((item) => !item.roles || hasRole(me, ...item.roles)).map((item) => (
              <NavLink key={item.to} to={item.to} end={item.to === "/"} className={link}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="text-teal-50">
              {me.displayName}{" "}
              <span className="text-teal-200">({me.roles.map((r) => r.toLowerCase()).join(", ") || "no role"})</span>
            </span>
            <button type="button" className="rounded-md px-2 py-1 hover:bg-teal-600" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
