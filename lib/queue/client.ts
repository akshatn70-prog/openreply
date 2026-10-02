/**
 * Supabase PGMQ queue client.
 *
 * Replaces BullMQ + Redis for DM processing. The queue itself is stored in
 * Supabase Postgres via PGMQ. Successful jobs are deleted immediately.
 */
import { Pool } from "pg";

let pool: Pool | null = null;

function getQueuePool(): Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      max: Number(process.env.QUEUE_DB_POOL_MAX ?? 8),
      idleTimeoutMillis: 10_000,
      connectionTimeoutMillis: 10_000,
    });
  }
  return pool;
}

export type CommentSource = "WEBHOOK" | "POLLING";

export interface ProcessCommentJob {
  accountConnectionId?: string;
  instagramAccountId: string;
  commentId: string;
  commentText: string;
  commenterId: string;
  commenterName?: string;
  mediaId: string;
  originalMediaId?: string;
  requeueAttempt?: number;
  source?: CommentSource;
}

export interface ProcessPostbackJob {
  accountConnectionId?: string;
  instagramAccountId: string;
  userId: string;
  payload: string;
  mid?: string;
  fallback?: boolean;
  followRecheck?: boolean;
  followRecheckAttempt?: number;
}

export interface ProcessFollowUpJob {
  accountConnectionId?: string;
  instagramAccountId: string;
  userId: string;
  automationId: string;
  commenterName?: string | null;
}

export interface ProcessMessageJob {
  accountConnectionId?: string;
  instagramAccountId: string;
  messageId: string;
  messageText: string;
  senderId: string;
}

export type DmQueueJob =
  | ProcessCommentJob
  | ProcessPostbackJob
  | ProcessFollowUpJob
  | ProcessMessageJob;

export const POSTBACK_JOB_NAME = "process-postback";
export const FOLLOWUP_JOB_NAME = "process-followup";
export const MESSAGE_JOB_NAME = "process-message";

export interface DmQueueAddOptions {
  delay?: number;
  jobId?: string;
}

export interface DmQueueMessage {
  id: string;
  name: string;
  data: DmQueueJob;
  attemptsMade: number;
  messageId: string;
}

type QueueRow = {
  msg_id: string | number;
  read_ct: string | number;
  message: DmQueueJobPayload;
};

type DmQueueJobPayload = {
  name: string;
  data: DmQueueJob;
};

export interface DmQueueCounts {
  waiting: number;
  delayed: number;
  active: number;
  failed: number;
}

async function query<T = unknown>(text: string, values: unknown[] = []) {
  return getQueuePool().query<T>(text, values);
}

export async function enqueueDMJob<T extends DmQueueJob>(
  name: string,
  data: T,
  options: DmQueueAddOptions = {},
): Promise<string | null> {
  const delaySeconds = Math.max(0, Math.ceil((options.delay ?? 0) / 1000));
  const dedupKey = options.jobId ?? null;
  const result = await query<{ id: string | number | null }>(
    "select public.openreply_enqueue_dm_job($1::jsonb,$2::integer,$3::text) as id",
    [JSON.stringify({ name, data }), delaySeconds, dedupKey],
  );
  const id = result.rows[0]?.id;
  return id == null ? null : String(id);
}

export function getDMQueue() {
  return {
    add: enqueueDMJob,
  };
}

export async function readDMJobs(
  quantity = 1,
  visibilityTimeoutSeconds = 900,
  pollSeconds = 5,
): Promise<DmQueueMessage[]> {
  const result = await query<QueueRow>(
    "select msg_id, read_ct, message from pgmq.read_with_poll('dm_processing',$1::integer,$2::integer,$3::integer,100,'{}'::jsonb)",
    [visibilityTimeoutSeconds, quantity, pollSeconds],
  );

  return result.rows.map((row) => ({
    id: String(row.msg_id),
    messageId: String(row.msg_id),
    name: row.message.name,
    data: row.message.data,
    attemptsMade: Math.max(0, Number(row.read_ct) - 1),
  }));
}

export async function deleteDMJob(messageId: string): Promise<void> {
  await query("select pgmq.delete('dm_processing',$1::bigint)", [messageId]);
}

export async function cleanupQueueData(): Promise<void> {
  await query("select public.openreply_cleanup_queue_data()");
}

export async function getDMQueueCounts(): Promise<DmQueueCounts> {
  const result = await query<{
    queue_length: string | number;
  }>("select queue_length from pgmq.metrics('dm_processing')");
  return {
    waiting: Number(result.rows[0]?.queue_length ?? 0),
    delayed: 0,
    active: 0,
    failed: 0,
  };
}

export async function closeDMQueue(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = null;
  }
}
