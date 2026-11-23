import { afterEach, describe, expect, it, vi } from "vitest";

import { browser, csrfHeaders, resetForTests, safeReturnTo, signIn, signOut, SIGN_IN_URL } from "./session";

describe("safeReturnTo", () => {
  it("only allows paths on this site", () => {
    expect(safeReturnTo("/purchase-orders/12")).toBe("/purchase-orders/12");
    expect(safeReturnTo("//evil.test/phish")).toBe("/");
    expect(safeReturnTo("https://evil.test")).toBe("/");
    expect(safeReturnTo("/\\evil.test")).toBe("/");
    expect(safeReturnTo(null)).toBe("/");
  });
});

describe("csrfHeaders", () => {
  afterEach(() => {
    document.cookie = "ERP-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("copies the CSRF cookie into the header the API checks", () => {
    document.cookie = "ERP-XSRF-TOKEN=abc%3D123";
    expect(csrfHeaders()).toEqual({ "X-XSRF-TOKEN": "abc=123" });
  });

  it("sends nothing when there is no cookie yet", () => {
    expect(csrfHeaders()).toEqual({});
  });
});

describe("signIn and signOut", () => {
  afterEach(() => {
    resetForTests();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("redirects once, however many requests fail at the same time", () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    signIn("/sales-orders/7");
    signIn("/sales-orders/8");
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith(SIGN_IN_URL);
    expect(sessionStorage.getItem("erp.returnTo")).toBe("/sales-orders/7");
  });

  it("signs out of the app, then sends the browser to Keycloak's logout", async () => {
    document.cookie = "ERP-XSRF-TOKEN=tok";
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ logoutUrl: "http://localhost:8880/logout?x=1" })));
    vi.stubGlobal("fetch", fetchMock);

    await signOut();

    expect(fetchMock).toHaveBeenCalledWith("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": "tok" } });
    expect(assign).toHaveBeenCalledWith("http://localhost:8880/logout?x=1");
    document.cookie = "ERP-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });
});
