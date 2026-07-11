import { describe, it, expect, vi, beforeEach } from "vitest";
import { handleResponseError, isAuthPassthrough } from "./http";

// ---------------------------------------------------------------------------
// isAuthPassthrough
// ---------------------------------------------------------------------------

describe("isAuthPassthrough", () => {
  it("returns true for /api/auth/login", () => {
    expect(isAuthPassthrough("/api/auth/login")).toBe(true);
  });

  it("returns true for /api/auth/me", () => {
    expect(isAuthPassthrough("/api/auth/me")).toBe(true);
  });

  it("returns false for /api/repos", () => {
    expect(isAuthPassthrough("/api/repos")).toBe(false);
  });

  it("returns false for /api/chat/sessions", () => {
    expect(isAuthPassthrough("/api/chat/sessions")).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// handleResponseError
// ---------------------------------------------------------------------------

describe("handleResponseError", () => {
  let mockReload: ReturnType<typeof vi.fn>;
  let mockRemoveItem: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockReload = vi.fn();
    mockRemoveItem = vi.fn();

    // window is not available in node env — define it on globalThis before each test.
    Object.defineProperty(globalThis, "window", {
      value: { location: { reload: mockReload } },
      writable: true,
      configurable: true,
    });

    // localStorage is not available in node env — define it on globalThis before each test.
    Object.defineProperty(globalThis, "localStorage", {
      value: { removeItem: mockRemoveItem, getItem: vi.fn(() => null) },
      writable: true,
      configurable: true,
    });
  });

  /** Build a minimal axios-like error object. */
  function makeAxiosError(status: number, url: string, message?: string) {
    return {
      response: {
        status,
        data: message != null ? { message } : undefined,
      },
      config: { url },
      message: "Request failed with status code " + status,
    };
  }

  it("auth endpoint 401 does NOT call reload, does NOT call localStorage.removeItem", async () => {
    const err = makeAxiosError(401, "/api/auth/login");
    await expect(handleResponseError(err)).rejects.toThrow();
    expect(mockReload).not.toHaveBeenCalled();
    expect(mockRemoveItem).not.toHaveBeenCalled();
  });

  it("normal API 401 DOES call reload AND calls removeItem for all 3 keys", async () => {
    const err = makeAxiosError(401, "/api/repos");
    await expect(handleResponseError(err)).rejects.toThrow();
    expect(mockReload).toHaveBeenCalledOnce();
    expect(mockRemoveItem).toHaveBeenCalledTimes(3);
    expect(mockRemoveItem).toHaveBeenCalledWith("repolens.token");
    expect(mockRemoveItem).toHaveBeenCalledWith("repolens.userId");
    expect(mockRemoveItem).toHaveBeenCalledWith("repolens.username");
  });

  it("me endpoint 401 does NOT reload", async () => {
    const err = makeAxiosError(401, "/api/auth/me");
    await expect(handleResponseError(err)).rejects.toThrow();
    expect(mockReload).not.toHaveBeenCalled();
    expect(mockRemoveItem).not.toHaveBeenCalled();
  });

  it("non-401 error (500) does NOT reload and error message propagates", async () => {
    const err = makeAxiosError(500, "/api/repos", "Internal Server Error");
    await expect(handleResponseError(err)).rejects.toThrow("Internal Server Error");
    expect(mockReload).not.toHaveBeenCalled();
    expect(mockRemoveItem).not.toHaveBeenCalled();
  });
});
