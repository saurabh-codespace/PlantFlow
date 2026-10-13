import { afterEach, describe, expect, it, vi } from "vitest";
import { configureApi, request } from "./api";
afterEach(() => vi.unstubAllGlobals());
describe("authenticated API capability", () => {
  it("gets a fresh token, sets bearer header and omits cookies", async () => {
    const provider = vi.fn().mockResolvedValue("memory-only-token");
    configureApi(provider);
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: 3 }),
    });
    vi.stubGlobal("fetch", fetch);
    await request("/trips", { method: "POST", body: { seats: 1 } });
    expect(provider).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0][0]).toBe("/api/trips");
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe(
      "Bearer memory-only-token",
    );
    expect(fetch.mock.calls[0][1].credentials).toBe("omit");
  });
  it("rejects unsafe paths before obtaining a token", async () => {
    const provider = vi.fn();
    configureApi(provider);
    for (const path of [
      "https://other.example/api",
      "//other.example/api",
      "/../other",
    ])
      await expect(request(path)).rejects.toThrow("Invalid API path");
    expect(provider).not.toHaveBeenCalled();
  });
  it("preserves server conflict messages", async () => {
    configureApi(async () => "token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: async () => ({ message: "Vehicle is already assigned" }),
      }),
    );
    await expect(request("/trips")).rejects.toMatchObject({
      message: "Vehicle is already assigned",
      status: 409,
    });
  });
  it("reports offline failures clearly", async () => {
    configureApi(async () => "token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await expect(request("/trips")).rejects.toThrow("Cannot reach the server");
  });
});
