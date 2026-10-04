import {
  getDMQueue,
  getDMQueueCounts,
  type DmQueueJob,
} from "@/lib/queue/client";
import {
  parseTelegramJob,
  readTelegramJobs,
  type TelegramQueueEnvelope,
  type TelegramUpdate,
} from "@/lib/telegram/queue";
import { processJob, UnrecoverableError } from "@/lib/queue/dm-worker";
import {
  getWorkerAlerts,
  getWorkerHealth,
  recordWorkerAlert,
} from "@/lib/ops/worker-health";

const BACKOFF_DELAYS = [5 * 60 * 1000, 15 * 60 * 1000, 45 * 60 * 1000];

const sleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

function configured(): boolean {
  return Boolean(
    process.env.TELEGRAM_QUEUE_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_WORKER_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_QUEUE_CHAT_ID?.trim(),
  );
}

function workerToken(): string {
  const token = process.env.TELEGRAM_WORKER_BOT_TOKEN?.trim();
  if (!token) throw new Error("Missing TELEGRAM_WORKER_BOT_TOKEN");
  return token;
}

function commandChatId(): string {
  const value =
    process.env.TELEGRAM_COMMAND_CHAT_ID?.trim() ||
    process.env.TELEGRAM_QUEUE_CHAT_ID?.trim();
  if (!value) throw new Error("Missing TELEGRAM_QUEUE_CHAT_ID");
  return value;
}

async function telegramApi<T>(
  method: string,
  body: Record<string, unknown>,
): Promise<T> {
  const response = await fetch(
    `https://api.telegram.org/bot${workerToken()}/${method}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    },
  );
  const json = (await response.json()) as {
    ok: boolean;
    result?: T;
    description?: string;
  };
  if (!response.ok || !json.ok) {
    throw new Error(
      `Telegram ${method} failed: ${json.description ?? response.statusText}`,
    );
  }
  return json.result as T;
}

async function reply(chatId: string | number, text: string): Promise<void> {
  await telegramApi("sendMessage", {
    chat_id: chatId,
    text,
    disable_web_page_preview: true,
  });
}

async function registerCommands(): Promise<void> {
  await telegramApi("setMyCommands", {
    commands: [
      { command: "start", description: "Show what this bot does" },
      { command: "help", description: "Show available commands" },
      { command: "status", description: "Show real worker health" },
      { command: "queue", description: "Show real Supabase queue depth" },
      { command: "alerts", description: "Show recent real worker alerts" },
      { command: "transport", description: "Show Telegram transport configuration" },
    ],
  });
}

async function handleCommand(update: TelegramUpdate): Promise<boolean> {
  const message = update.message;
  const text = message?.text?.trim();
  if (message?.chat?.id == null || !text?.startsWith("/")) return false;

  if (String(message.chat.id) !== commandChatId()) return true;

  const command = text.split(/\s+/)[0].split("@")[0].toLowerCase();

  try {
    if (command === "/start" || command === "/help") {
      await reply(
        message.chat.id,
        [
          "OpenReply Telegram Worker",
          "",
          "Receives Telegram-routed OpenReply jobs and runs the same DM worker logic as the normal queue.",
          "",
          "/status — real worker heartbeat/health",
          "/queue — real Supabase PGMQ queue depth",
          "/alerts — latest real worker alerts",
          "/transport — real Telegram transport configuration",
          "/help — this help",
        ].join("\n"),
      );
      return true;
    }

    if (command === "/status") {
      const health = await getWorkerHealth();
      const age =
        health.ageMs == null
          ? "unknown"
          : `${Math.round(health.ageMs / 1000)}s ago`;
      await reply(
        message.chat.id,
        [
          "OpenReply Worker Status",
          `Health: ${health.healthy ? "HEALTHY" : "UNHEALTHY"}`,
          `Heartbeat: ${age}`,
          `PID: ${health.heartbeat?.pid ?? "unknown"}`,
          `Host: ${health.heartbeat?.hostname ?? "unknown"}`,
          `Started: ${health.heartbeat?.startedAt ?? "unknown"}`,
        ].join("\n"),
      );
      return true;
    }

    if (command === "/queue") {
      const counts = await getDMQueueCounts();
      await reply(
        message.chat.id,
        [
          "OpenReply Queue",
          `Supabase PGMQ waiting: ${counts.waiting}`,
          "Telegram jobs are delivered through the configured Telegram queue chat and consumed by long polling.",
        ].join("\n"),
      );
      return true;
    }

    if (command === "/alerts") {
      const alerts = await getWorkerAlerts(5);
      await reply(
        message.chat.id,
        alerts.length
          ? [
              "Latest OpenReply Worker Alerts",
              "",
              ...alerts.map(
                (alert) =>
                  `[${alert.level.toUpperCase()}] ${new Date(alert.createdAt).toLocaleString()}\n${alert.message}`,
              ),
            ].join("\n")
          : "OpenReply Worker Alerts\n\nNo worker alerts recorded.",
      );
      return true;
    }

    if (command === "/transport") {
      await reply(
        message.chat.id,
        [
          "Telegram Processing Transport",
          `Configured: ${configured() ? "YES" : "NO"}`,
          `Queue chat ID: ${process.env.TELEGRAM_QUEUE_CHAT_ID?.trim() ?? "missing"}`,
          `Command chat ID: ${process.env.TELEGRAM_COMMAND_CHAT_ID?.trim() ?? process.env.TELEGRAM_QUEUE_CHAT_ID?.trim() ?? "missing"}`,
          "Worker receives jobs with getUpdates long polling.",
          "Supabase campaigns continue using the existing PGMQ path.",
        ].join("\n"),
      );
      return true;
    }
  } catch (error) {
    await reply(
      message.chat.id,
      `Command failed: ${error instanceof Error ? error.message : String(error)}`,
    );
    return true;
  }

  await reply(message.chat.id, "Unknown command. Use /help.");
  return true;
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
    await registerCommands();
    console.log("[Telegram Worker] Started");
    while (!stopping) {
      try {
        const updates = await readTelegramJobs(offset);
        for (const update of updates) {
          if (stopping) break;
          const envelope = parseTelegramJob(update);
          if (!envelope) {
            await handleCommand(update);
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
