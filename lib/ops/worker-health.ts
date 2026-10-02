import { Pool } from "pg";

const WORKER_ID = "dm";
const WORKER_HEARTBEAT_TTL_SECONDS = 120;

let pool: Pool | null = null;
function getPool(): Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      max: Number(process.env.WORKER_HEALTH_DB_POOL_MAX ?? 2),
      idleTimeoutMillis: 10_000,
      connectionTimeoutMillis: 10_000,
    });
  }
  return pool;
}

export interface WorkerHeartbeat {
  status: "running";
  worker: "dm";
  pid: number;
  hostname?: string;
  startedAt?: string;
  checkedAt: string;
}

export interface WorkerHealth {
  healthy: boolean;
  heartbeat: WorkerHeartbeat | null;
  ageMs: number | null;
}

export interface WorkerAlert {
  level: "warning" | "error";
  message: string;
  jobId?: string;
  instagramAccountId?: string;
  commentId?: string;
  createdAt: string;
}

export async function recordWorkerHeartbeat(
  heartbeat: Omit<WorkerHeartbeat, "checkedAt" | "status" | "worker">,
) {
  await getPool().query(
    "select public.openreply_record_worker_heartbeat($1,$2,$3,$4)",
    [WORKER_ID, heartbeat.pid, heartbeat.hostname ?? null, heartbeat.startedAt ?? null],
  );
}

export async function getWorkerHealth(): Promise<WorkerHealth> {
  const result = await getPool().query<{
    status: string; pid: number; hostname: string | null;
    started_at: string | null; checked_at: string;
  }>(
    "select status,pid,hostname,started_at,checked_at from public.openreply_worker_health where worker_id=$1",
    [WORKER_ID],
  );
  const row = result.rows[0];
  if (!row) return { healthy: false, heartbeat: null, ageMs: null };

  const checkedAt = new Date(row.checked_at);
  const ageMs = Date.now() - checkedAt.getTime();
  return {
    healthy: row.status === "running" && ageMs <= WORKER_HEARTBEAT_TTL_SECONDS * 1000,
    heartbeat: {
      status: "running",
      worker: "dm",
      pid: Number(row.pid),
      hostname: row.hostname ?? undefined,
      startedAt: row.started_at ?? undefined,
      checkedAt: checkedAt.toISOString(),
    },
    ageMs,
  };
}

export async function recordWorkerAlert(alert: Omit<WorkerAlert, "createdAt">) {
  await getPool().query(
    "insert into public.openreply_worker_alert(level,message,job_id,instagram_account_id,comment_id) values($1,$2,$3,$4,$5)",
    [alert.level, alert.message, alert.jobId ?? null, alert.instagramAccountId ?? null, alert.commentId ?? null],
  );
  await getPool().query(
    "delete from public.openreply_worker_alert where created_at < now() - interval '2 days'",
  );
}

export async function getWorkerAlerts(limit = 10): Promise<WorkerAlert[]> {
  const result = await getPool().query<{
    level: "warning" | "error"; message: string; job_id: string | null;
    instagram_account_id: string | null; comment_id: string | null; created_at: string;
  }>(
    "select level,message,job_id,instagram_account_id,comment_id,created_at from public.openreply_worker_alert order by created_at desc limit $1",
    [Math.max(1, Math.min(limit, 100))],
  );
  return result.rows.map((row) => ({
    level: row.level,
    message: row.message,
    jobId: row.job_id ?? undefined,
    instagramAccountId: row.instagram_account_id ?? undefined,
    commentId: row.comment_id ?? undefined,
    createdAt: new Date(row.created_at).toISOString(),
  }));
}

export { WORKER_HEARTBEAT_TTL_SECONDS };
