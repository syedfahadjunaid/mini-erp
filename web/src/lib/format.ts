import type { Me, Role } from "../api/types";

/**
 * Display helpers. Money never becomes a float here: amounts arrive as integer minor units with
 * a server-formatted string, and typed prices are parsed exactly into minor units.
 */

/** How many decimals a currency has (EUR 2, JPY 0), per ISO 4217 as the browser knows it. */
export function currencyDecimals(currency: string): number {
  try {
    return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * "19.99" in EUR -> 1999. Exact string arithmetic (no floating point): rejects more decimals than
 * the currency has, negative amounts and anything that isn't a plain number. Returns null if invalid.
 */
export function parseMoney(text: string, currency: string): number | null {
  const decimals = currencyDecimals(currency);
  const match = /^(\d{1,15})(?:[.,](\d+))?$/.exec(text.trim());
  if (!match) return null;
  const whole = match[1] ?? "0";
  const fraction = match[2] ?? "";
  if (fraction.length > decimals) return null;
  return Number(whole + fraction.padEnd(decimals, "0"));
}

/** 1999 in EUR -> "19.99" (for pre-filling inputs; the API's `formatted` is used for display). */
export function minorToText(amountMinor: number, currency: string): string {
  const decimals = currencyDecimals(currency);
  if (decimals === 0) return String(amountMinor);
  const sign = amountMinor < 0 ? "-" : "";
  const digits = String(Math.abs(amountMinor)).padStart(decimals + 1, "0");
  return `${sign}${digits.slice(0, -decimals)}.${digits.slice(-decimals)}`;
}

/** A typed quantity: positive, at most 3 decimals (NUMERIC(18,3) on the server). Null if invalid. */
export function parseQuantity(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d{1,12}(?:\.\d{1,3})?$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return value > 0 ? value : null;
}

/** "PARTIALLY_RECEIVED" -> "Partially received". */
export function label(enumValue: string): string {
  const words = enumValue.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** 2000 basis points -> "20%", 550 -> "5.5%". */
export function percent(basisPoints: number): string {
  return `${(basisPoints / 100).toString()}%`;
}

const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

export function dateTime(iso: string | null | undefined): string {
  return iso ? dateTimeFormat.format(new Date(iso)) : "—";
}

/** Signed quantities in the ledger: "+12", "−3", "0". */
export function signed(value: number): string {
  if (value > 0) return `+${value}`;
  if (value < 0) return `−${Math.abs(value)}`;
  return "0";
}

/** Whether the user has any of these roles. For showing menus and forms only: the server decides. */
export function hasRole(me: Me, ...roles: Role[]): boolean {
  return roles.some((role) => me.roles.includes(role));
}
