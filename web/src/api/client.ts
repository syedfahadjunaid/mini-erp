import { csrfHeaders, signIn } from "../auth/session";
import type { Shortage } from "./types";

export type InvalidField = { field: string; message: string };

/** An API call that failed, with what the UI needs to explain it (RFC 9457 problem details). */
export class ApiError extends Error {
  readonly status: number;
  /** Machine-readable reason, e.g. "insufficient_stock", "separation_of_duties". */
  readonly code: string | undefined;
  readonly fieldErrors: InvalidField[];
  readonly shortages: Shortage[];

  constructor(status: number, message: string, code?: string, fieldErrors: InvalidField[] = [], shortages: Shortage[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.shortages = shortages;
  }

  /** The server's message for one input, e.g. messageFor("lines[0].quantity"). */
  messageFor(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message;
  }
}

type ProblemDetail = { detail?: string; code?: string; errors?: InvalidField[]; shortages?: Shortage[] };

type RequestOptions = {
  method?: "GET" | "POST" | "PUT";
  body?: unknown;
  signal?: AbortSignal;
};

/**
 * fetch() wrapper for the API: sends and parses JSON, adds the CSRF header to requests that
 * change something, and turns problem responses into {@link ApiError}s. No token handling on
 * purpose: the browser sends its HttpOnly session cookie and the server does the rest.
 */
export async function apiFetch<T>(path: string, { method = "GET", body, signal }: RequestOptions = {}): Promise<T> {
  const response = await fetch(path, {
    method,
    signal,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(method === "GET" ? {} : csrfHeaders()),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    // POST/PUT always send a JSON body, so the content type is never a form's.
    body: body === undefined ? (method === "GET" ? undefined : "{}") : JSON.stringify(body),
  });

  if (response.status === 401) {
    signIn();
    throw new ApiError(401, "Your session has expired. Signing you in again…");
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return null as T;
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail = {};
  try {
    problem = (await response.json()) as ProblemDetail;
  } catch {
    // Some errors (e.g. 403 from Spring Security) have no body.
  }
  return new ApiError(
    response.status,
    problem.detail ?? defaultMessage(response.status),
    problem.code,
    Array.isArray(problem.errors) ? problem.errors : [],
    Array.isArray(problem.shortages) ? problem.shortages : [],
  );
}

function defaultMessage(status: number): string {
  if (status === 403) return "You don't have permission to do that.";
  if (status === 404) return "That item doesn't exist.";
  if (status === 409) return "Someone else changed this at the same time. Reload and try again.";
  if (status >= 500) return "The server had a problem. Please try again.";
  return `Request failed (${status}).`;
}

/** Builds "?a=1&b=2" from the defined values only. */
export function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}
