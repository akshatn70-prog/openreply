/**
 * PostgreSQL/Supabase rate limiter for Instagram private replies.
 *
 * The reservation is atomic inside Postgres, so concurrent DM workers cannot
 * all pass the limit before incrementing it.
 */
const RATE_LIMIT_MAX = 750;
const RATE_LIMIT_WINDOW = 3600;
const REQUEUE_DELAY_MS = 30 * 60 * 1000;
const MAX_REQUEUE_ATTEMPTS = 3;

import { Pool } from "pg";

let pool: Pool | null = null;

function getPool(): Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      max: Number(process.env.RATE_LIMIT_DB_POOL_MAX ?? 3),
      idleTimeoutMillis: 10_000,
      connectionTimeoutMillis: 10_000,
    });
  }
  return pool;
}

export interface RateLimitResult {
  allowed: boolean;
  currentCount: number;
  remainingDMs: number;
  shouldRequeue: boolean;
  requeueDelayMs: number;
  shouldSkip: boolean;
  reserved: boolean;
}

function blockedResult(count: number, requeueAttempt: number): RateLimitResult {
  if (requeueAttempt >= MAX_REQUEUE_ATTEMPTS) {
    return {
      allowed: false, currentCount: count, remainingDMs: 0,
      shouldRequeue: false, requeueDelayMs: 0, shouldSkip: true, reserved: false,
    };
  }
  return {
    allowed: false, currentCount: count, remainingDMs: 0,
    shouldRequeue: true, requeueDelayMs: REQUEUE_DELAY_MS,
    shouldSkip: false, reserved: false,
  };
}

export async function checkRateLimit(
  instagramAccountId: string,
  requeueAttempt = 0,
): Promise<RateLimitResult> {
  const result = await getPool().query<{ allowed: boolean; current_count: number; remaining: number }>(
    "select * from public.openreply_reserve_dm_slot($1,$2,$3)",
    [instagramAccountId, RATE_LIMIT_MAX, RATE_LIMIT_WINDOW],
  );

  const row = result.rows[0];
  if (!row) throw new Error("Rate limiter returned no result");

  if (!row.allowed) return blockedResult(Number(row.current_count), requeueAttempt);

  // checkRateLimit historically did not reserve. Preserve that contract by
  // immediately releasing the reservation; workers should use reserveDMSlot().
  await getPool().query("select public.openreply_release_dm_slot($1)", [instagramAccountId]);

  return {
    allowed: true,
    currentCount: Number(row.current_count),
    remainingDMs: Number(row.remaining),
    shouldRequeue: false, requeueDelayMs: 0, shouldSkip: false, reserved: false,
  };
}

export async function reserveDMSlot(
  instagramAccountId: string,
  requeueAttempt = 0,
): Promise<RateLimitResult> {
  const result = await getPool().query<{ allowed: boolean; current_count: number; remaining: number }>(
    "select * from public.openreply_reserve_dm_slot($1,$2,$3)",
    [instagramAccountId, RATE_LIMIT_MAX, RATE_LIMIT_WINDOW],
  );
  const row = result.rows[0];
  if (!row) throw new Error("Rate limiter returned no result");

  if (!row.allowed) return blockedResult(Number(row.current_count), requeueAttempt);

  return {
    allowed: true,
    currentCount: Number(row.current_count),
    remainingDMs: Number(row.remaining),
    shouldRequeue: false, requeueDelayMs: 0, shouldSkip: false, reserved: true,
  };
}

export async function releaseDMSlot(instagramAccountId: string): Promise<number> {
  const result = await getPool().query<{ openreply_release_dm_slot: number }>(
    "select public.openreply_release_dm_slot($1)",
    [instagramAccountId],
  );
  return Number(result.rows[0]?.openreply_release_dm_slot ?? 0);
}

export async function incrementDMCounter(instagramAccountId: string): Promise<number> {
  const result = await reserveDMSlot(instagramAccountId, MAX_REQUEUE_ATTEMPTS);
  return result.currentCount;
}

export async function getCurrentDMCount(instagramAccountId: string): Promise<number> {
  const result = await getPool().query<{ reserved_count: number }>(
    "select reserved_count from public.openreply_dm_rate_limit where instagram_account_id=$1",
    [instagramAccountId],
  );
  return Number(result.rows[0]?.reserved_count ?? 0);
}

export async function resetRateLimit(instagramAccountId: string): Promise<void> {
  await getPool().query(
    "delete from public.openreply_dm_rate_limit where instagram_account_id=$1",
    [instagramAccountId],
  );
}

export { RATE_LIMIT_MAX, RATE_LIMIT_WINDOW, REQUEUE_DELAY_MS, MAX_REQUEUE_ATTEMPTS };
