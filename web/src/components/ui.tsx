import type { ButtonHTMLAttributes, ReactNode } from "react";

import { ApiError } from "../api/client";

/** Small building blocks. Tailwind classes only: the CSP forbids inline <style> and style attributes in HTML. */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" };

export function Button({ variant = "primary", className = "", type = "button", ...props }: ButtonProps) {
  const look = {
    primary: "bg-teal-700 text-white hover:bg-teal-800 disabled:bg-teal-300",
    secondary: "bg-white text-slate-800 ring-1 ring-slate-300 hover:bg-slate-50 disabled:text-slate-400",
    danger: "bg-red-600 text-white hover:bg-red-700 disabled:bg-red-300",
  }[variant];
  return (
    <button
      type={type}
      className={`inline-flex items-center gap-1 rounded-md px-3 py-1.5 text-sm font-medium shadow-sm transition-colors disabled:cursor-not-allowed ${look} ${className}`}
      {...props}
    />
  );
}

/** A link that looks like a button: for downloads (the browser sends the session cookie). */
export function DownloadLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a
      href={href}
      className="inline-flex items-center gap-1 rounded-md bg-white px-3 py-1.5 text-sm font-medium text-slate-800 shadow-sm ring-1 ring-slate-300 hover:bg-slate-50"
    >
      {children}
    </a>
  );
}

export function Badge({ className = "", children }: { className?: string; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${className}`}>
      {children}
    </span>
  );
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg bg-white shadow-sm ring-1 ring-slate-200">
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
          <h2 className="text-sm font-semibold text-slate-800">{title}</h2>
          <div className="flex flex-wrap gap-2">{actions}</div>
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
        {subtitle && <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Spinner({ label = "Loading…" }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 text-sm text-slate-500">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-teal-700" />
      {label}
    </div>
  );
}

/**
 * Explains a failed request: the server's message, plus each short product for
 * "insufficient_stock" problems. `names` turns product ids into SKUs.
 */
export function ErrorText({ error, names }: { error: unknown; names?: (productId: number) => string }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : "Something went wrong.";
  const shortages = error instanceof ApiError ? error.shortages : [];
  return (
    <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-red-200">
      <p>{message}</p>
      {shortages.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {shortages.map((s) => (
            <li key={`${s.productId}-${s.binId ?? "w"}`}>
              {names ? names(s.productId) : `Product ${s.productId}`}: {s.available} available, {s.requested} needed
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-slate-500">{children}</p>;
}

export function FullPageMessage({ title, message, busy, children }: {
  title: string;
  message?: string;
  busy?: boolean;
  children?: ReactNode;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <div className="max-w-md space-y-4 text-center">
        <h1 className="text-xl font-semibold">{title}</h1>
        {message && <p className="text-slate-600">{message}</p>}
        {busy && <div className="flex justify-center"><Spinner label="" /></div>}
        {children}
      </div>
    </main>
  );
}

export function Label({ htmlFor, children }: { htmlFor: string; children: ReactNode }) {
  return (
    <label htmlFor={htmlFor} className="block text-xs font-medium uppercase tracking-wide text-slate-500">
      {children}
    </label>
  );
}

/** A label, an input (the child) and the server's message for that field, if any. */
export function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: ReactNode }) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      {children}
      {error && <p className="mt-1 text-xs text-red-700">{error}</p>}
    </div>
  );
}

export const inputClass =
  "mt-1 block w-full rounded-md border-0 bg-white px-2.5 py-1.5 text-sm ring-1 ring-inset ring-slate-300 focus:ring-2 focus:ring-teal-600";

export const tableClass = "min-w-full divide-y divide-slate-200 text-sm";
export const thClass = "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-slate-500";
export const thNumClass = "px-3 py-2 text-right text-xs font-semibold uppercase tracking-wide text-slate-500";
export const tdClass = "px-3 py-2 align-top text-slate-700";
export const tdNumClass = "px-3 py-2 text-right align-top tabular-nums text-slate-700";

/** Horizontal scrolling for wide tables on small screens. */
export function TableWrap({ children }: { children: ReactNode }) {
  return <div className="-mx-4 overflow-x-auto px-4">{children}</div>;
}

export function Definition({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{term}</dt>
      <dd className="mt-0.5 text-sm text-slate-800">{children}</dd>
    </div>
  );
}
