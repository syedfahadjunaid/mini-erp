import { Fragment, useState } from "react";

import { useInvoices } from "../api/hooks";
import type { Me } from "../api/types";
import { useNames } from "../components/useNames";
import {
  Card, DownloadLink, EmptyState, ErrorText, PageHeader, Spinner, TableWrap, tableClass, tdClass, tdNumClass, thClass, thNumClass,
} from "../components/ui";
import { percent } from "../lib/format";

/**
 * Issued invoices. They're created automatically when goods ship, numbered INV-year-n with no
 * gaps, and never change afterwards (a correction would be a credit note).
 */
export function InvoicesPage({ me }: { me: Me }) {
  const invoices = useInvoices();
  const names = useNames();
  const [open, setOpen] = useState<number | null>(null);

  return (
    <>
      <PageHeader title="Invoices" subtitle={`One per shipment, consecutive numbers per year${me.roles.includes("FINANCE") ? "" : " (read-only for you)"}.`} />
      <Card>
        {invoices.isPending && <Spinner />}
        <ErrorText error={invoices.error} />
        {invoices.data?.length === 0 && <EmptyState>No invoices yet: ship a sales order.</EmptyState>}
        {invoices.data && invoices.data.length > 0 && (
          <TableWrap>
            <table className={tableClass}>
              <thead>
                <tr>
                  <th className={thClass}>Invoice</th>
                  <th className={thClass}>Customer</th>
                  <th className={thClass}>Issued</th>
                  <th className={thClass}>Due</th>
                  <th className={thNumClass}>Net</th>
                  <th className={thNumClass}>Tax</th>
                  <th className={thNumClass}>Total</th>
                  <th className={thClass} />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {invoices.data.map((i) => (
                  <Fragment key={i.id}>
                    <tr className="cursor-pointer hover:bg-slate-50" onClick={() => setOpen(open === i.id ? null : i.id)}>
                      <td className={`${tdClass} font-medium`}>{i.invoiceNumber}</td>
                      <td className={tdClass}>{i.billToName}</td>
                      <td className={tdClass}>{i.issueDate}</td>
                      <td className={tdClass}>{i.dueDate}</td>
                      <td className={tdNumClass}>{i.netTotal.formatted}</td>
                      <td className={tdNumClass}>{i.taxTotal.formatted}</td>
                      <td className={`${tdNumClass} font-semibold`}>{i.grossTotal.formatted} {i.currency}</td>
                      <td className={tdClass} onClick={(e) => e.stopPropagation()}>
                        <DownloadLink href={`/api/invoices/${i.id}/pdf`}>PDF</DownloadLink>
                      </td>
                    </tr>
                    {open === i.id && (
                      <tr>
                        <td colSpan={8} className="bg-slate-50 px-3 py-2 text-sm">
                          <ul className="space-y-0.5">
                            {i.lines.map((l) => (
                              <li key={l.lineNo}>
                                {l.lineNo}. {l.description} ({names.sku(l.productId)}): {l.quantity} {l.uom} × {l.unitPrice.formatted}
                                {" "}= {l.net.formatted} + {percent(l.taxRateBp)} tax {l.tax.formatted}
                              </li>
                            ))}
                          </ul>
                          <p className="mt-1 whitespace-pre-line text-slate-500">Bill to: {i.billToName}, {i.billToAddress.replaceAll("\n", ", ")}</p>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </TableWrap>
        )}
      </Card>
    </>
  );
}
