import { type FormEvent, type ReactNode, useState } from "react";

import { ApiError } from "../api/client";
import {
  useAddBin, useCreateCustomer, useCreateProduct, useCreateSupplier, useCreateWarehouse, useReorderRules, useSetReorderRule,
  useUnitsOfMeasure,
} from "../api/hooks";
import type { Me } from "../api/types";
import { useNames } from "../components/useNames";
import {
  Badge, Button, Card, EmptyState, ErrorText, Field, inputClass, PageHeader, TableWrap, tableClass, tdClass, tdNumClass, thClass,
  thNumClass,
} from "../components/ui";
import { hasRole, percent } from "../lib/format";

type Tab = "products" | "warehouses" | "partners" | "reorder";
const TABS: { id: Tab; label: string }[] = [
  { id: "products", label: "Products" },
  { id: "warehouses", label: "Warehouses & bins" },
  { id: "partners", label: "Suppliers & customers" },
  { id: "reorder", label: "Reorder points" },
];

/** Everything documents refer to. Everyone can look; ADMIN creates, MANAGER sets reorder points. */
export function MasterDataPage({ me }: { me: Me }) {
  const [tab, setTab] = useState<Tab>("products");
  const admin = hasRole(me, "ADMIN");
  return (
    <>
      <PageHeader title="Master data" />
      <div className="mb-4 flex flex-wrap gap-1 border-b border-slate-200" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${tab === t.id ? "border-teal-700 text-teal-800" : "border-transparent text-slate-600 hover:text-slate-900"}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "products" && <Products canCreate={admin} />}
      {tab === "warehouses" && <Warehouses canCreate={admin} />}
      {tab === "partners" && <Partners canCreate={admin} />}
      {tab === "reorder" && <ReorderRules canSet={hasRole(me, "MANAGER")} />}
    </>
  );
}

const fieldError = (error: unknown, field: string) => (error instanceof ApiError ? error.messageFor(field) : undefined);

function Products({ canCreate }: { canCreate: boolean }) {
  const { products } = useNames();
  const units = useUnitsOfMeasure();
  const create = useCreateProduct();
  const [form, setForm] = useState({ sku: "", name: "", baseUom: "EA", taxRateBp: "2000", extraUom: "", factor: "" });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const extra = form.extraUom && Number(form.factor) > 0 ? [{ uom: form.extraUom, baseUnitsPerUom: Number(form.factor) }] : [];
    create.mutate(
      { sku: form.sku.trim().toUpperCase(), name: form.name.trim(), baseUom: form.baseUom, taxRateBp: Number(form.taxRateBp), units: extra },
      { onSuccess: () => setForm({ ...form, sku: "", name: "", extraUom: "", factor: "" }) },
    );
  };

  return (
    <div className="space-y-4">
      {canCreate && (
        <Card title="New product">
          <form onSubmit={submit} className="grid gap-3 sm:grid-cols-7">
            <Field id="p-sku" label="SKU" error={fieldError(create.error, "sku")}>
              <input id="p-sku" className={inputClass} value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
            </Field>
            <div className="sm:col-span-2">
              <Field id="p-name" label="Name" error={fieldError(create.error, "name")}>
                <input id="p-name" className={inputClass} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </Field>
            </div>
            <Field id="p-base" label="Base unit">
              <select id="p-base" className={inputClass} value={form.baseUom} onChange={(e) => setForm({ ...form, baseUom: e.target.value })}>
                {units.data?.map((u) => <option key={u.code} value={u.code}>{u.code} · {u.name}</option>)}
              </select>
            </Field>
            <Field id="p-tax" label="Tax (basis points)" error={fieldError(create.error, "taxRateBp")}>
              <input id="p-tax" className={inputClass} inputMode="numeric" value={form.taxRateBp} onChange={(e) => setForm({ ...form, taxRateBp: e.target.value })} />
            </Field>
            <Field id="p-extra" label="Also sold in" error={fieldError(create.error, "units[0].uom")}>
              <select id="p-extra" className={inputClass} value={form.extraUom} onChange={(e) => setForm({ ...form, extraUom: e.target.value })}>
                <option value="">—</option>
                {units.data?.filter((u) => u.code !== form.baseUom).map((u) => <option key={u.code} value={u.code}>{u.code}</option>)}
              </select>
            </Field>
            <Field id="p-factor" label="Base units per">
              <input id="p-factor" className={inputClass} inputMode="numeric" placeholder="e.g. 12" value={form.factor} onChange={(e) => setForm({ ...form, factor: e.target.value })} />
            </Field>
            <div className="sm:col-span-7 flex items-center gap-3">
              <Button type="submit" disabled={create.isPending}>Create product</Button>
              <ErrorText error={create.error} />
            </div>
          </form>
        </Card>
      )}
      <Card title="Products">
        {products?.length === 0 && <EmptyState>No products yet.</EmptyState>}
        <TableWrap>
          <table className={tableClass}>
            <thead>
              <tr>
                <th className={thClass}>SKU</th>
                <th className={thClass}>Name</th>
                <th className={thClass}>Units</th>
                <th className={thNumClass}>Tax</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {products?.map((p) => (
                <tr key={p.id}>
                  <td className={`${tdClass} font-medium`}>{p.sku}</td>
                  <td className={tdClass}>{p.name} {!p.active && <Badge className="ring-slate-300">inactive</Badge>}</td>
                  <td className={tdClass}>{p.units.map((u) => (u.baseUnitsPerUom === 1 ? u.uom : `${u.uom} = ${u.baseUnitsPerUom} ${p.baseUom}`)).join(" · ")}</td>
                  <td className={tdNumClass}>{percent(p.taxRateBp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>
      </Card>
    </div>
  );
}

function Warehouses({ canCreate }: { canCreate: boolean }) {
  const { warehouses } = useNames();
  const create = useCreateWarehouse();
  const addBin = useAddBin();
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [binCodes, setBinCodes] = useState<Record<number, string>>({});

  return (
    <div className="space-y-4">
      {canCreate && (
        <Card title="New warehouse">
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate({ code: code.trim().toUpperCase(), name: name.trim() }, { onSuccess: () => { setCode(""); setName(""); } });
            }}
          >
            <Field id="w-code" label="Code" error={fieldError(create.error, "code")}>
              <input id="w-code" className={inputClass} value={code} onChange={(e) => setCode(e.target.value)} />
            </Field>
            <Field id="w-name" label="Name" error={fieldError(create.error, "name")}>
              <input id="w-name" className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Button type="submit" disabled={create.isPending}>Create (with a RECEIVING bin)</Button>
            <ErrorText error={create.error} />
          </form>
        </Card>
      )}
      <div className="grid gap-4 md:grid-cols-2">
        {warehouses?.map((w) => (
          <Card key={w.id} title={`${w.code} · ${w.name}`}>
            <div className="flex flex-wrap gap-1.5">
              {w.bins.map((b) => (
                <Badge key={b.id} className={b.receiving ? "bg-amber-50 text-amber-800 ring-amber-300" : "bg-slate-50 text-slate-700 ring-slate-300"}>
                  {b.code}
                </Badge>
              ))}
            </div>
            {canCreate && (
              <form
                className="mt-3 flex items-center gap-2"
                onSubmit={(e) => {
                  e.preventDefault();
                  addBin.mutate(
                    { warehouseId: w.id, code: (binCodes[w.id] ?? "").trim().toUpperCase() },
                    { onSuccess: () => setBinCodes({ ...binCodes, [w.id]: "" }) },
                  );
                }}
              >
                <input
                  aria-label={`New bin code for ${w.code}`}
                  placeholder="New bin, e.g. A-02-01"
                  className={`${inputClass} mt-0 w-48`}
                  value={binCodes[w.id] ?? ""}
                  onChange={(e) => setBinCodes({ ...binCodes, [w.id]: e.target.value })}
                />
                <Button type="submit" variant="secondary" disabled={addBin.isPending}>Add bin</Button>
              </form>
            )}
          </Card>
        ))}
      </div>
      <ErrorText error={addBin.error} />
    </div>
  );
}

function Partners({ canCreate }: { canCreate: boolean }) {
  const { suppliers, customers } = useNames();
  const createSupplier = useCreateSupplier();
  const createCustomer = useCreateCustomer();
  const [s, setS] = useState({ code: "", name: "", currency: "EUR", terms: "30" });
  const [c, setC] = useState({ code: "", name: "", line1: "", postalCode: "", city: "", country: "NL", currency: "EUR", terms: "14" });

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="Suppliers">
        {canCreate && (
          <form
            className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4"
            onSubmit={(e) => {
              e.preventDefault();
              createSupplier.mutate(
                { code: s.code.trim().toUpperCase(), name: s.name.trim(), currency: s.currency.trim().toUpperCase(), paymentTermsDays: Number(s.terms) },
                { onSuccess: () => setS({ ...s, code: "", name: "" }) },
              );
            }}
          >
            <Input id="s-code" label="Code" value={s.code} onChange={(v) => setS({ ...s, code: v })} error={fieldError(createSupplier.error, "code")} />
            <Input id="s-name" label="Name" value={s.name} onChange={(v) => setS({ ...s, name: v })} error={fieldError(createSupplier.error, "name")} />
            <Input id="s-ccy" label="Currency" value={s.currency} onChange={(v) => setS({ ...s, currency: v })} error={fieldError(createSupplier.error, "currency")} />
            <Input id="s-terms" label="Terms (days)" value={s.terms} onChange={(v) => setS({ ...s, terms: v })} />
            <div className="col-span-2 flex items-center gap-2 sm:col-span-4">
              <Button type="submit" disabled={createSupplier.isPending}>Add supplier</Button>
              <ErrorText error={createSupplier.error} />
            </div>
          </form>
        )}
        <PartnerList rows={suppliers?.map((x) => [x.code, x.name, `${x.currency} · ${x.paymentTermsDays} days`]) ?? []} />
      </Card>
      <Card title="Customers">
        {canCreate && (
          <form
            className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4"
            onSubmit={(e) => {
              e.preventDefault();
              createCustomer.mutate(
                {
                  code: c.code.trim().toUpperCase(), name: c.name.trim(), addressLine1: c.line1.trim(), postalCode: c.postalCode.trim(),
                  city: c.city.trim(), countryCode: c.country.trim().toUpperCase(), currency: c.currency.trim().toUpperCase(),
                  paymentTermsDays: Number(c.terms),
                },
                { onSuccess: () => setC({ ...c, code: "", name: "", line1: "", postalCode: "", city: "" }) },
              );
            }}
          >
            <Input id="c-code" label="Code" value={c.code} onChange={(v) => setC({ ...c, code: v })} error={fieldError(createCustomer.error, "code")} />
            <Input id="c-name" label="Name" value={c.name} onChange={(v) => setC({ ...c, name: v })} error={fieldError(createCustomer.error, "name")} />
            <Input id="c-line1" label="Address" value={c.line1} onChange={(v) => setC({ ...c, line1: v })} error={fieldError(createCustomer.error, "addressLine1")} />
            <Input id="c-postal" label="Postal code" value={c.postalCode} onChange={(v) => setC({ ...c, postalCode: v })} error={fieldError(createCustomer.error, "postalCode")} />
            <Input id="c-city" label="City" value={c.city} onChange={(v) => setC({ ...c, city: v })} error={fieldError(createCustomer.error, "city")} />
            <Input id="c-country" label="Country (2 letters)" value={c.country} onChange={(v) => setC({ ...c, country: v })} error={fieldError(createCustomer.error, "countryCode")} />
            <Input id="c-ccy" label="Currency" value={c.currency} onChange={(v) => setC({ ...c, currency: v })} error={fieldError(createCustomer.error, "currency")} />
            <Input id="c-terms" label="Terms (days)" value={c.terms} onChange={(v) => setC({ ...c, terms: v })} />
            <div className="col-span-2 flex items-center gap-2 sm:col-span-4">
              <Button type="submit" disabled={createCustomer.isPending}>Add customer</Button>
              <ErrorText error={createCustomer.error} />
            </div>
          </form>
        )}
        <PartnerList rows={customers?.map((x) => [x.code, x.name, `${x.address.city}, ${x.address.countryCode} · ${x.currency}`]) ?? []} />
      </Card>
    </div>
  );
}

function Input({ id, label, value, onChange, error }: { id: string; label: string; value: string; onChange: (v: string) => void; error?: string }) {
  return (
    <Field id={id} label={label} error={error}>
      <input id={id} className={inputClass} value={value} onChange={(e) => onChange(e.target.value)} />
    </Field>
  );
}

function PartnerList({ rows }: { rows: [string, string, ReactNode][] }) {
  if (rows.length === 0) return <EmptyState>None yet.</EmptyState>;
  return (
    <ul className="divide-y divide-slate-100 text-sm">
      {rows.map(([code, name, extra]) => (
        <li key={code} className="flex justify-between gap-2 py-1.5">
          <span><span className="font-medium">{code}</span> · {name}</span>
          <span className="text-slate-500">{extra}</span>
        </li>
      ))}
    </ul>
  );
}

function ReorderRules({ canSet }: { canSet: boolean }) {
  const names = useNames();
  const rules = useReorderRules();
  const set = useSetReorderRule();
  const [form, setForm] = useState({ warehouseId: "", productId: "", point: "", quantity: "" });

  return (
    <div className="space-y-4">
      {canSet && (
        <Card title="Set a reorder point">
          <form
            className="grid gap-3 sm:grid-cols-5"
            onSubmit={(e) => {
              e.preventDefault();
              set.mutate({
                warehouseId: Number(form.warehouseId),
                productId: Number(form.productId),
                reorderPoint: Number(form.point),
                reorderQuantity: Number(form.quantity),
              });
            }}
          >
            <Field id="r-w" label="Warehouse">
              <select id="r-w" className={inputClass} value={form.warehouseId} onChange={(e) => setForm({ ...form, warehouseId: e.target.value })}>
                <option value="">Choose…</option>
                {names.warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
              </select>
            </Field>
            <Field id="r-p" label="Product">
              <select id="r-p" className={inputClass} value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>
                <option value="">Choose…</option>
                {names.products?.map((p) => <option key={p.id} value={p.id}>{p.sku}</option>)}
              </select>
            </Field>
            <Field id="r-point" label="Alert at or below" error={fieldError(set.error, "reorderPoint")}>
              <input id="r-point" className={inputClass} inputMode="numeric" value={form.point} onChange={(e) => setForm({ ...form, point: e.target.value })} />
            </Field>
            <Field id="r-qty" label="Reorder quantity" error={fieldError(set.error, "reorderQuantity")}>
              <input id="r-qty" className={inputClass} inputMode="numeric" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
            </Field>
            <div className="flex items-end">
              <Button type="submit" disabled={set.isPending}>Save</Button>
            </div>
          </form>
          <div className="mt-2"><ErrorText error={set.error} /></div>
        </Card>
      )}
      <Card title="Reorder points">
        {rules.data?.length === 0 && <EmptyState>No reorder points set.</EmptyState>}
        <TableWrap>
          <table className={tableClass}>
            <thead>
              <tr>
                <th className={thClass}>Warehouse</th>
                <th className={thClass}>Product</th>
                <th className={thNumClass}>Available</th>
                <th className={thNumClass}>Reorder point</th>
                <th className={thNumClass}>Reorder quantity</th>
                <th className={thClass}>Set by</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rules.data?.map((r) => (
                <tr key={`${r.warehouseId}-${r.productId}`}>
                  <td className={tdClass}>{r.warehouseCode}</td>
                  <td className={tdClass}>{r.sku}</td>
                  <td className={`${tdNumClass} ${r.available <= r.reorderPoint ? "font-semibold text-red-700" : ""}`}>{r.available}</td>
                  <td className={tdNumClass}>{r.reorderPoint}</td>
                  <td className={tdNumClass}>{r.reorderQuantity}</td>
                  <td className={tdClass}>{r.updatedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>
      </Card>
    </div>
  );
}
