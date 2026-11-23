import { afterEach, describe, expect, it, vi } from "vitest";

import { browser, resetForTests, SIGN_IN_URL } from "../auth/session";
import { ApiError, apiFetch, query } from "./client";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("apiFetch", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    resetForTests();
    document.cookie = "ERP-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("sends JSON with the CSRF header on POST, and none on GET", async () => {
    document.cookie = "ERP-XSRF-TOKEN=t0k";
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(json(200, { ok: true })));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/x", { method: "POST", body: { a: 1 } });
    await apiFetch("/api/y");

    const [, post] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(post.headers).toMatchObject({ "X-XSRF-TOKEN": "t0k", "Content-Type": "application/json" });
    expect(post.body).toBe('{"a":1}');
    const [, get] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(get.headers).not.toHaveProperty("X-XSRF-TOKEN");
  });

  it("turns a problem response into an ApiError with the code, field errors and shortages", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(422, {
      detail: "Not enough available stock to confirm SO-000001.",
      code: "insufficient_stock",
      errors: [{ field: "lines[0].quantity", message: "too much" }],
      shortages: [{ warehouseId: 1, binId: null, productId: 7, available: 3, requested: 5 }],
    })));

    const error = await apiFetch("/api/sales-orders/1/actions/confirm", { method: "POST" }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(422);
    expect(apiError.code).toBe("insufficient_stock");
    expect(apiError.messageFor("lines[0].quantity")).toBe("too much");
    expect(apiError.shortages[0]).toMatchObject({ productId: 7, available: 3, requested: 5 });
  });

  it("starts a sign-in when the session is gone", async () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    await expect(apiFetch("/api/me")).rejects.toMatchObject({ status: 401 });
    expect(assign).toHaveBeenCalledWith(SIGN_IN_URL);
  });
});

describe("query", () => {
  it("keeps only the values that are set", () => {
    expect(query({ warehouseId: 3, productId: undefined, status: "" })).toBe("?warehouseId=3");
    expect(query({})).toBe("");
  });
});
