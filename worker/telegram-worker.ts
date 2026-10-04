import {
  getDMQueue,
  type DmQueueJob,
} from "@/lib/queue/client";
import {
  parseTelegramJob,
  readTelegramJobs,
  type TelegramQueueEnvelope,
} from "@/lib/telegram/queue";
import { processJob, UnrecoverableError } from "@/lib/queue/dm-worker";
import { recordWorkerAlert } from "@/lib/ops/worker-health";

const BACKOFF_DELAYS = [5 * 60 * 1000, 15 * 60 * 1000, 45 * 60 * 1000];

const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

function configured(): boolean {
  return Boolean(
    process.env.TELEGRAM_QUEUE_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_WORKER_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_WORKER_BOT_USERNAME?.trim(),
  );
}

async function handleEnvelope(envelope: TelegramQueueEnvelope): Promise<void> {
  const job = {
    id: envelope.id,
    name: envelope.name,
    data: envelope.data as DmQueueJob,
    attemptsMade: envelope.attemptsMade,
  };

  if (envelope.notBefore && envelope.notBefore > Date.now()) {
    await sleep(envelope.notBefore - Date.now());
  }

  try {
    await processJob(job);
  } catch (error) {
    const err = error instanceof Error ? error : new Error(String(error));
    const attempts = envelope.attemptsMade + 1;
    const terminal = err instanceof UnrecoverableError || attempts >= 3;

    if (terminal) {
      await recordWorkerAlert({
        level: "error",
        message: `Telegram job ${envelope.id} failed permanently (attempt ${attempts}): ${err.message}`,
        jobId: envelope.id,
        instagramAccountId:
          typeof envelope.data === "object" &&
          envelope.data &&
          "instagramAccountId" in envelope.data
            ? String(envelope.data.instagramAccountId)
            : undefined,
        commentId:
          typeof envelope.data === "object" &&
          envelope.data &&
          "commentId" in envelope.data
            ? String(envelope.data.commentId)
            : undefined,
      });
      return;
    }

    await getDMQueue().add(
      envelope.name,
      {
        ...envelope.data,
        processingTransport: "TELEGRAM",
      } as DmQueueJob,
      {
        delay: BACKOFF_DELAYS[Math.min(attempts - 1, BACKOFF_DELAYS.length - 1)],
        jobId: `${envelope.id}:retry:${attempts}`,
      },
    );
  }
}

export function startTelegramWorker(): { close: () => void } {
  if (!configured()) {
    return { close: () => undefined };
  }

  let stopping = false;
  let offset: number | undefined;

  const loop = (async () => {
    console.log("[Telegram Worker] Started");
    while (!stopping) {
      try {
        const updates = await readTelegramJobs(offset);
        for (const update of updates) {
          if (stopping) break;
          const envelope = parseTelegramJob(update);
          if (!envelope) {
            offset = update.update_id + 1;
            continue;
          }

          // Do not advance offset until the job has either completed or has
          // been durably re-sent with its retry delay. Telegram keeps pending
          // updates for up to 24 hours.
          await handleEnvelope(envelope);
          offset = update.update_id + 1;
        }
      } catch (error) {
        console.error(
          "[Telegram Worker] Update loop failed:",
          error instanceof Error ? error.message : String(error),
        );
        await sleep(2000);
      }
    }
    console.log("[Telegram Worker] Stopped");
  })();

  return {
    close() {
      stopping = true;
      void loop;
    },
  };
}
