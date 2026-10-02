import { NextResponse } from "next/server";
import { prisma } from "@/lib/db/client";
import { getDMQueueCounts } from "@/lib/queue/client";
import { getWorkerHealth } from "@/lib/ops/worker-health";

export const runtime = "nodejs";
// Health must reflect live state (worker heartbeat, queue depth), never a
// cached response, or it reports stale worker start times.
export const dynamic = "force-dynamic";

type CheckStatus = "ok" | "error";

interface HealthCheck {
  status: CheckStatus;
  detail?: string;
}

async function checkDatabase(): Promise<HealthCheck> {
  try {
    await prisma.$queryRaw`SELECT 1`;
    return { status: "ok" };
  } catch (error) {
    return {
      status: "error",
      detail: error instanceof Error ? error.message : "Database check failed",
    };
  }
}

// A backlog with nothing in flight is the signal, and it is unambiguous: a
// healthy worker with a concurrency of 5 never leaves jobs waiting with zero
// active. The threshold only exists to ride out the moment between a job being
// enqueued and the worker picking it up.
const STUCK_QUEUE_MIN_WAITING = Number(
  process.env.HEALTH_STUCK_QUEUE_WAITING ?? 25
);

async function checkQueue(): Promise<HealthCheck & { counts?: unknown }> {
  try {
    const counts = await getDMQueueCounts();
    const waiting = counts.waiting ?? 0;
    const active = counts.active ?? 0;
    if (waiting >= STUCK_QUEUE_MIN_WAITING && active === 0) {
      return {
        status: "error",
        detail: `${waiting} jobs waiting with none active — the worker is not consuming`,
        counts,
      };
    }
    return { status: "ok", counts };
  } catch (error) {
    return {
      status: "error",
      detail: error instanceof Error ? error.message : "Queue check failed",
    };
  }
}

export async function GET() {
  const [database, queue, worker] = await Promise.all([
    checkDatabase(),
    checkQueue(),
    getWorkerHealth().catch((error) => ({
      healthy: false,
      heartbeat: null,
      ageMs: null,
      error: error instanceof Error ? error.message : "Worker check failed",
    })),
  ]);

  const healthy =
    database.status === "ok" &&
    queue.status === "ok" &&
    worker.healthy;

  return NextResponse.json(
    {
      status: healthy ? "ok" : "degraded",
      checks: {
        database,
        queue,
        worker,
      },
    },
    { status: healthy ? 200 : 503 }
  );
}
