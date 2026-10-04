import { prisma } from '@/lib/db/client';
import { getDMQueue, MESSAGE_JOB_NAME, POSTBACK_JOB_NAME } from '@/lib/queue/client';
import { parseCommentEvents, parseMessageEvents, parsePostbackEvents, parseReadEvents } from '@/lib/meta/webhook';
import { Prisma } from '@/app/generated/prisma/client';
import { matchKeywords } from '@/lib/utils/keyword-matcher';
import type { ProcessingTransport } from '@/lib/queue/client';

const OPENING_DM_READ_FALLBACK_DELAY_MS = 5 * 60 * 1000;

async function commentTransports(
  accountId: string,
  mediaId: string,
  originalMediaId: string | undefined,
  commentText: string,
): Promise<ProcessingTransport[]> {
  const automations = await prisma.automation.findMany({
    where: {
      instagramAccountId: accountId,
      isActive: true,
      OR: [
        { postId: mediaId },
        ...(originalMediaId ? [{ postId: originalMediaId }] : []),
        { matchAnyPost: true },
      ],
    },
    select: {
      processingTransport: true,
      matchAnyWord: true,
      keywords: true,
      wholeWordMatch: true,
    },
  });

  const transports = new Set<ProcessingTransport>();
  for (const automation of automations) {
    const matched = automation.matchAnyWord
      ? true
      : matchKeywords(commentText, automation.keywords, automation.wholeWordMatch).matched;
    if (matched) transports.add(automation.processingTransport);
  }
  return [...transports];
}

async function messageTransports(
  accountId: string,
  messageText: string,
): Promise<ProcessingTransport[]> {
  const automations = await prisma.automation.findMany({
    where: {
      instagramAccountId: accountId,
      isActive: true,
      dmTriggerEnabled: true,
    },
    select: {
      processingTransport: true,
      matchAnyWord: true,
      keywords: true,
      wholeWordMatch: true,
    },
  });

  const transports = new Set<ProcessingTransport>();
  for (const automation of automations) {
    const matched = automation.matchAnyWord
      ? true
      : matchKeywords(messageText, automation.keywords, automation.wholeWordMatch).matched;
    if (matched) transports.add(automation.processingTransport);
  }
  return [...transports];
}
type InstagramPayload = Parameters<typeof parseCommentEvents>[0];

export async function processInstagramWebhook({ payload: incoming, workspaceId }: { payload: InstagramPayload; workspaceId?: string }) {
  if (incoming.object !== 'instagram' || !Array.isArray(incoming.entry)) return;
  const accounts = await prisma.instagramAccount.findMany({
    where: { instagramId: { in: incoming.entry.map(e => e.id) }, ...(workspaceId ? { workspaceId } : {}) },
    select: { id: true, instagramId: true, workspaceId: true },
  });
  const accountMap = new Map(accounts.map(a => [a.instagramId, a]));
  const allowed = new Set(accountMap.keys());
  const payload = { ...incoming, entry: incoming.entry.filter(e => allowed.has(e.id)) };
  if (!payload.entry.length) return;
  const webhookEvent = await prisma.webhookEvent.create({
    data: {
      object:
        typeof payload === "object" && payload && "object" in payload
          ? String(payload.object)
          : null,
      payload: payload as unknown as Prisma.InputJsonValue,
      ...(workspaceId ? { workspaceId } : {}),
      status: "PENDING",
    },
  });

  try {
    const commentEvents = parseCommentEvents(
      payload as Parameters<typeof parseCommentEvents>[0]
    );
    const queue = getDMQueue();

    for (const event of commentEvents) {
      const account = accountMap.get(event.instagramAccountId);
      if (!account) continue;

      const transports = await commentTransports(
        accountMap.get(event.instagramAccountId)!.id,
        event.mediaId,
        event.originalMediaId,
        event.commentText,
      );
      for (const processingTransport of transports) {
        await queue.add(
          "process-comment",
          {
            processingTransport,
            instagramAccountId: event.instagramAccountId,
            accountConnectionId: accountMap.get(event.instagramAccountId)?.id,
            commentId: event.commentId,
            commentText: event.commentText,
            commenterId: event.commenterId,
            commenterName: event.commenterName,
            mediaId: event.mediaId,
            originalMediaId: event.originalMediaId,
            source: "WEBHOOK",
          },
          {
            jobId: `comment_${event.instagramAccountId}_${event.commentId}_${processingTransport.toLowerCase()}`,
          }
        );
      }

      if (account) {
        await prisma.webhookEvent.update({
          where: { id: webhookEvent.id },
          data: { workspaceId: account.workspaceId },
        });
      }
    }

    // Button taps from opening DMs → deliver the reveal message.
    const postbackEvents = parsePostbackEvents(
      payload as Parameters<typeof parsePostbackEvents>[0]
    );

    for (const event of postbackEvents) {
      const automationId = event.payload.split(":")[1]?.split(":")[0];
      const automation = automationId
        ? await prisma.automation.findFirst({
            where: { id: automationId, isActive: true },
            select: { processingTransport: true },
          })
        : null;
      if (!automation) continue;

      await queue.add(
        POSTBACK_JOB_NAME,
        {
          processingTransport: automation.processingTransport,
          instagramAccountId: event.instagramAccountId,
          accountConnectionId: accountMap.get(event.instagramAccountId)?.id,
          userId: event.userId,
          payload: event.payload,
          mid: event.mid,
        },
        {
          jobId: `postback_${event.instagramAccountId}_${event.userId}_${(
            event.mid ?? event.payload
          ).replace(/:/g, "_")}_${automation.processingTransport.toLowerCase()}`,
        }
      );
    }

    // Inbound DMs → keyword-triggered autoreply.
    const messageEvents = parseMessageEvents(
      payload as Parameters<typeof parseMessageEvents>[0]
    );

    for (const event of messageEvents) {
      const account = accountMap.get(event.instagramAccountId);
      if (!account) continue;

      const transports = await messageTransports(
        accountMap.get(event.instagramAccountId)!.id,
        event.messageText,
      );
      for (const processingTransport of transports) {
        await queue.add(
          MESSAGE_JOB_NAME,
          {
            processingTransport,
            instagramAccountId: event.instagramAccountId,
            accountConnectionId: accountMap.get(event.instagramAccountId)?.id,
            messageId: event.messageId,
            messageText: event.messageText,
            senderId: event.senderId,
          },
          {
            jobId: `message_${event.instagramAccountId}_${Buffer.from(
              event.messageId
            ).toString("base64url")}_${processingTransport.toLowerCase()}`,
          }
        );
      }

      if (account) {
        await prisma.webhookEvent.update({
          where: { id: webhookEvent.id },
          data: { workspaceId: account.workspaceId },
        });
      }
    }

    // If a user reads the opening DM and never taps the button, deliver the
    // same next-step DM after five minutes. The worker no-ops this delayed job
    // if a real button tap has already delivered the reveal.
    const readEvents = parseReadEvents(
      payload as Parameters<typeof parseReadEvents>[0]
    );

    for (const event of readEvents) {
      const openingLogs = await prisma.dmLog.findMany({
        where: {
          commenterId: event.userId,
          status: "SENT",
          automation: {
            isActive: true,
            openingDmEnabled: true,
            instagramAccount: {
              instagramId: event.instagramAccountId,
            },
          },
        },
        select: {
          automation: {
            select: {
              id: true,
              processingTransport: true,
            },
          },
        },
      });

      const scheduledAutomationIds = new Set<string>();
      for (const log of openingLogs) {
        const automation = log.automation;
        if (scheduledAutomationIds.has(automation.id)) continue;
        scheduledAutomationIds.add(automation.id);

        await queue.add(
          POSTBACK_JOB_NAME,
          {
            processingTransport: automation.processingTransport,
            instagramAccountId: event.instagramAccountId,
            accountConnectionId: accountMap.get(event.instagramAccountId)?.id,
            userId: event.userId,
            payload: `reveal:${automation.id}`,
            fallback: true,
          },
          {
            delay: OPENING_DM_READ_FALLBACK_DELAY_MS,
            jobId: `read_fallback_${event.instagramAccountId}_${event.userId}_${automation.id}`,
          }
        );
      }
    }

    await prisma.webhookEvent.update({
      where: { id: webhookEvent.id },
      data: {
        status: "PROCESSED",
        processedAt: new Date(),
      },
    });

    return;
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    await prisma.webhookEvent.update({
      where: { id: webhookEvent.id },
      data: {
        status: "FAILED",
        errorMessage: message,
        processedAt: new Date(),
      },
    });

    throw error;
  }
}
