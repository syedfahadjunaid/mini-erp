import { type FormEvent, useState } from "react";

import { useCreateTransfer, useReceiveTransfer, useTransfers } from "../api/hooks";
import type { Me, Transfer } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { useNames } from "../components/useNames";
import {
  Button, Card, EmptyState, ErrorText, Field, inputClass, PageHeader, Spinner, TableWrap, tableClass, tdClass, thClass,
} from "../components/ui";
import { dateTime, hasRole, parseQuantity } from "../lib/format";

/**
 * Moving stock between warehouses, in two steps: dispatch (it leaves the source bin and is in
 * transit) and receive (it arrives in a destination bin). Reserved stock can't be sent away.
 */
export function TransfersPage({ me }: { me: Me }) {
  const transfers = useTransfers();
  const canMove = hasRole(me, "WAREHOUSE");
  return (
    <>
      <PageHeader title="Transfers" subtitle="Only available stock can leave a warehouse: what's reserved for customers stays." />
      <div className="space-y-4">
        {canMove && <NewTransferForm />}
        <Card title="Recent transfers">
          {transfers.isPending && <Spinner />}
          <ErrorText error={transfers.error} />
          {transfers.data?.length === 0 && <EmptyState>No transfers yet.</EmptyState>}
          {transfers.data && transfers.data.length > 0 && (
            <TableWrap>
              <table className={tableClass}>
                <thead>
                  <tr>
                    <th className={thClass}>Transfer</th>
                    <th className={thClass}>Route</th>
                    <th className={thClass}>Lines</th>
                    <th className={thClass}>Status</th>
                    <th className={thClass}>Dispatched</th>
                    <th className={thClass}>Received</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {transfers.data.map((t) => <TransferRow key={t.id} transfer={t} canReceive={canMove} />)}
                </tbody>
              </table>
            </TableWrap>
          )}
        </Card>
      </div>
    </>
  );
}

function TransferRow({ transfer: t, canReceive }: { transfer: Transfer; canReceive: boolean }) {
  const names = useNames();
  const receive = useReceiveTransfer();
  const bins = names.warehouses?.find((w) => w.id === t.toWarehouseId)?.bins.filter((b) => b.active) ?? [];
  const [toBin, setToBin] = useState("");
  return (
    <tr>
      <td className={`${tdClass} font-medium`}>{t.transferNumber}</td>
      <td className={tdClass}>{names.warehouse(t.fromWarehouseId)} → {names.warehouse(t.toWarehouseId)}</td>
      <td className={tdClass}>
        {t.lines.map((l) => `${l.quantity} × ${names.sku(l.productId)} (${names.bin(l.fromBinId)} → ${names.bin(l.toBinId)})`).join("; ")}
      </td>
      <td className={tdClass}><StatusBadge status={t.status} /></td>
      <td className={tdClass}>{dateTime(t.createdAt)} · {t.createdBy}</td>
      <td className={tdClass}>
        {t.status === "RECEIVED" && `${dateTime(t.receivedAt)} · ${t.receivedBy}`}
        {t.status === "IN_TRANSIT" && canReceive && (
          <div className="flex flex-wrap items-center gap-2">
            <select aria-label={`Destination bin for ${t.transferNumber}`} className={`${inputClass} mt-0 w-40`} value={toBin} onChange={(e) => setToBin(e.target.value)}>
              <option value="">Receiving bin</option>
              {bins.filter((b) => !b.receiving).map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}
            </select>
            <Button disabled={receive.isPending} onClick={() => receive.mutate({ id: t.id, toBinId: toBin ? Number(toBin) : undefined })}>
              Receive
            </Button>
            <ErrorText error={receive.error} />
          </div>
        )}
      </td>
    </tr>
  );
}

function NewTransferForm() {
  const names = useNames();
  const create = useCreateTransfer();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [productId, setProductId] = useState("");
  const [binId, setBinId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const sourceBins = names.warehouses?.find((w) => String(w.id) === from)?.bins ?? [];

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amount = parseQuantity(quantity);
    if (!from || !to || !productId || !binId || amount === null) {
      setProblem("Choose both warehouses, a product, the source bin, and a quantity above zero.");
      return;
    }
    setProblem(null);
    create.mutate(
      { fromWarehouseId: Number(from), toWarehouseId: Number(to), lines: [{ productId: Number(productId), fromBinId: Number(binId), quantity: amount }] },
      { onSuccess: () => setQuantity("") },
    );
  };

  return (
    <Card title="Dispatch a transfer">
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-6">
        <Field id="tr-from" label="From">
          <select id="tr-from" className={inputClass} value={from} onChange={(e) => { setFrom(e.target.value); setBinId(""); }}>
            <option value="">Choose…</option>
            {names.warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
          </select>
        </Field>
        <Field id="tr-bin" label="From bin">
          <select id="tr-bin" className={inputClass} value={binId} onChange={(e) => setBinId(e.target.value)}>
            <option value="">Choose…</option>
            {sourceBins.map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}
          </select>
        </Field>
        <Field id="tr-to" label="To">
          <select id="tr-to" className={inputClass} value={to} onChange={(e) => setTo(e.target.value)}>
            <option value="">Choose…</option>
            {names.warehouses?.filter((w) => String(w.id) !== from).map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}
          </select>
        </Field>
        <Field id="tr-product" label="Product">
          <select id="tr-product" className={inputClass} value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">Choose…</option>
            {names.products?.map((p) => <option key={p.id} value={p.id}>{p.sku}</option>)}
          </select>
        </Field>
        <Field id="tr-qty" label="Quantity (base units)">
          <input id="tr-qty" className={inputClass} inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </Field>
        <div className="flex items-end">
          <Button type="submit" disabled={create.isPending}>Dispatch</Button>
        </div>
      </form>
      <div className="mt-3 space-y-2">
        {problem && <ErrorText error={new Error(problem)} />}
        <ErrorText error={create.error} names={names.product} />
      </div>
    </Card>
  );
}
