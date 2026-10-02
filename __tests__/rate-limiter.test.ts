import { describe, it, expect, vi, beforeEach } from "vitest";

const { mockQuery } = vi.hoisted(() => ({ mockQuery: vi.fn() }));

vi.mock("pg", () => ({
  Pool: vi.fn().mockImplementation(() => ({ query: mockQuery, end: vi.fn() })),
}));

import {
  checkRateLimit,
  incrementDMCounter,
  reserveDMSlot,
  releaseDMSlot,
  RATE_LIMIT_MAX,
} from "../lib/utils/rate-limiter";

beforeEach(() => {
  vi.clearAllMocks();
});

describe("checkRateLimit", () => {
  it("allows below the hourly limit and does not retain a reservation", async () => {
    mockQuery
      .mockResolvedValueOnce({ rows: [{ allowed: true, current_count: 50, remaining: RATE_LIMIT_MAX - 50 }] })
      .mockResolvedValueOnce({ rows: [{ openreply_release_dm_slot: 50 }] });

    const result = await checkRateLimit("account_123");

    expect(result.allowed).toBe(true);
    expect(result.currentCount).toBe(50);
    expect(result.remainingDMs).toBe(RATE_LIMIT_MAX - 50);
    expect(result.reserved).toBe(false);
    expect(mockQuery).toHaveBeenCalledTimes(2);
  });

  it("recommends requeue when the limit is reached", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ allowed: false, current_count: RATE_LIMIT_MAX, remaining: 0 }],
    });

    const result = await checkRateLimit("account_123");

    expect(result.allowed).toBe(false);
    expect(result.shouldRequeue).toBe(true);
    expect(result.shouldSkip).toBe(false);
  });

  it("skips after the maximum requeue attempts", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ allowed: false, current_count: RATE_LIMIT_MAX, remaining: 0 }],
    });

    const result = await checkRateLimit("account_123", 3);

    expect(result.allowed).toBe(false);
    expect(result.shouldRequeue).toBe(false);
    expect(result.shouldSkip).toBe(true);
  });
});

describe("reserveDMSlot", () => {
  it("atomically reserves a slot through Postgres", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ allowed: true, current_count: 51, remaining: 139 }],
    });

    const result = await reserveDMSlot("account_123");

    expect(result.allowed).toBe(true);
    expect(result.reserved).toBe(true);
    expect(result.currentCount).toBe(51);
    expect(result.remainingDMs).toBe(139);
    expect(mockQuery).toHaveBeenCalledWith(
      "select * from public.openreply_reserve_dm_slot($1,$2,$3)",
      ["account_123", RATE_LIMIT_MAX, 3600],
    );
  });

  it("recommends requeue when the atomic reservation is denied", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ allowed: false, current_count: RATE_LIMIT_MAX, remaining: 0 }],
    });

    const result = await reserveDMSlot("account_123", 0);

    expect(result.allowed).toBe(false);
    expect(result.reserved).toBe(false);
    expect(result.shouldRequeue).toBe(true);
  });
});

describe("incrementDMCounter", () => {
  it("uses the atomic reservation path", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ allowed: true, current_count: 51, remaining: 139 }],
    });

    const count = await incrementDMCounter("account_123");

    expect(count).toBe(51);
  });
});

describe("releaseDMSlot", () => {
  it("returns the new reservation count", async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ openreply_release_dm_slot: 49 }],
    });

    const count = await releaseDMSlot("account_123");

    expect(count).toBe(49);
    expect(mockQuery).toHaveBeenCalledWith(
      "select public.openreply_release_dm_slot($1)",
      ["account_123"],
    );
  });
});
