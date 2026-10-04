import type { DmQueueAddOptions, DmQueueJob } from "@/lib/queue/client";

export type ProcessingTransport = "SUPABASE" | "TELEGRAM";

export interface TelegramQueueEnvelope {
  v: 1;
  id: string;
  name: string;
  data: DmQueueJob & { processingTransport: "TELEGRAM" };
  attemptsMade: number;
  notBefore?: number;
}

const PREFIX = "OPENREPLY_JOB ";
const API = "https://api.telegram.org/bot";

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

async function callTelegram<T>(token: string, method: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${API}${token}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const json = (await response.json()) as { ok: boolean; result?: T; description?: string };
  if (!response.ok || !json.ok) {
    throw new Error(`Telegram ${method} failed: ${json.description ?? response.statusText}`);
  }
  return json.result as T;
}

export function telegramQueueConfigured(): boolean {
  return Boolean(
    process.env.TELEGRAM_QUEUE_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_WORKER_BOT_TOKEN?.trim() &&
      process.env.TELEGRAM_QUEUE_CHAT_ID?.trim(),
  );
}

export async function enqueueTelegramJob(
  name: string,
  data: DmQueueJob & { processingTransport: "TELEGRAM" },
  options: DmQueueAddOptions = {},
): Promise<string> {
  const producerToken = required("TELEGRAM_QUEUE_BOT_TOKEN");
  const queueChatId = required("TELEGRAM_QUEUE_CHAT_ID");
  const id = options.jobId ?? `tg_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  const envelope: TelegramQueueEnvelope = {
    v: 1,
    id,
    name,
    data,
    attemptsMade: 0,
    ...(options.delay && options.delay > 0
      ? { notBefore: Date.now() + options.delay }
      : {}),
  };
  const text = PREFIX + JSON.stringify(envelope);
  if (text.length > 4096) {
    throw new Error("Telegram queue payload exceeds Telegram's 4096-character message limit");
  }

  await callTelegram(producerToken, "sendMessage", {
    chat_id: queueChatId,
    text,
    disable_notification: true,
  });
  return id;
}

export interface TelegramUpdate {
  update_id: number;
  message?: { text?: string };
}

export async function readTelegramJobs(
  offset: number | undefined,
  signal?: AbortSignal,
): Promise<TelegramUpdate[]> {
  const token = required("TELEGRAM_WORKER_BOT_TOKEN");
  return callTelegram<TelegramUpdate[]>(token, "getUpdates", {
    ...(offset !== undefined ? { offset } : {}),
    limit: 20,
    timeout: 50,
    allowed_updates: ["message"],
  });
}

export function parseTelegramJob(update: TelegramUpdate): TelegramQueueEnvelope | null {
  const text = update.message?.text;
  if (!text?.startsWith(PREFIX)) return null;
  try {
    const parsed = JSON.parse(text.slice(PREFIX.length)) as TelegramQueueEnvelope;
    if (
      parsed?.v !== 1 ||
      typeof parsed.id !== "string" ||
      typeof parsed.name !== "string" ||
      typeof parsed.data !== "object" ||
      parsed.data?.processingTransport !== "TELEGRAM"
    ) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}
