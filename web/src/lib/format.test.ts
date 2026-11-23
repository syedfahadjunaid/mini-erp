import { describe, expect, it } from "vitest";

import { currencyDecimals, hasRole, label, minorToText, parseMoney, parseQuantity, percent, signed } from "./format";
import { emptyLine, toOrderLines } from "./orderLines";

describe("parseMoney", () => {
  it("turns typed prices into exact minor units", () => {
    expect(parseMoney("19.99", "EUR")).toBe(1999);
    expect(parseMoney("19,9", "EUR")).toBe(1990);
    expect(parseMoney("45", "EUR")).toBe(4500);
    expect(parseMoney("0.10", "EUR")).toBe(10); // 0.1 isn't exact in binary floating point; this is
    expect(parseMoney("1250", "JPY")).toBe(1250);
  });

  it("refuses what can't be an amount in that currency", () => {
    expect(parseMoney("19.999", "EUR")).toBeNull(); // more decimals than cents
    expect(parseMoney("12.5", "JPY")).toBeNull(); // yen have no minor unit
    expect(parseMoney("-3", "EUR")).toBeNull();
    expect(parseMoney("1e3", "EUR")).toBeNull();
    expect(parseMoney("", "EUR")).toBeNull();
  });

  it("knows each currency's decimals", () => {
    expect(currencyDecimals("EUR")).toBe(2);
    expect(currencyDecimals("JPY")).toBe(0);
  });

  it("formats minor units back for inputs", () => {
    expect(minorToText(1999, "EUR")).toBe("19.99");
    expect(minorToText(5, "EUR")).toBe("0.05");
    expect(minorToText(1250, "JPY")).toBe("1250");
  });
});

describe("parseQuantity", () => {
  it("accepts positive quantities with up to 3 decimals", () => {
    expect(parseQuantity("10")).toBe(10);
    expect(parseQuantity("1.5")).toBe(1.5);
    expect(parseQuantity("0.125")).toBe(0.125);
  });

  it("refuses zero, negatives and too many decimals", () => {
    expect(parseQuantity("0")).toBeNull();
    expect(parseQuantity("-1")).toBeNull();
    expect(parseQuantity("1.2345")).toBeNull();
    expect(parseQuantity("ten")).toBeNull();
  });
});

describe("display helpers", () => {
  it("labels, percentages and signs", () => {
    expect(label("PARTIALLY_RECEIVED")).toBe("Partially received");
    expect(percent(2000)).toBe("20%");
    expect(percent(550)).toBe("5.5%");
    expect(signed(12)).toBe("+12");
    expect(signed(-3)).toBe("−3");
  });

  it("checks roles", () => {
    const me = { username: "bea", displayName: "Bea", roles: ["PURCHASING", "MANAGER"] as const };
    expect(hasRole({ ...me, roles: [...me.roles] }, "MANAGER")).toBe(true);
    expect(hasRole({ ...me, roles: [...me.roles] }, "FINANCE", "SALES")).toBe(false);
  });
});

describe("toOrderLines", () => {
  it("converts typed lines into the API's shape", () => {
    const result = toOrderLines([{ productId: "7", uom: "BOX", quantity: "2.5", price: "45.00" }], "EUR");
    expect(result).toEqual({ lines: [{ productId: 7, uom: "BOX", quantity: 2.5, unitPriceMinor: 4500 }] });
  });

  it("names the first problem", () => {
    expect(toOrderLines([emptyLine], "EUR")).toEqual({ problem: "Line 1: choose a product." });
    expect(toOrderLines([{ productId: "7", uom: "EA", quantity: "3", price: "1.234" }], "EUR"))
      .toEqual({ problem: "Line 1: enter a price like 19.99." });
  });
});
