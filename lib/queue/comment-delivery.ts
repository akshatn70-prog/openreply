import { prisma } from "@/lib/db/client";

export const MAX_COMMENT_SEND_ATTEMPTS = 3;

// A durable, atomic claim precedes the external side effect. If the process
// crashes or the final log write fails, an uncertain send must not be repeated.
export async function claimCommentDelivery(
  automationId: string,
  commentId: string,
  leg: "dm" | "public",
): Promise<boolean> {
  const result = await prisma.dmLog.updateMany({
    where: {
      automationId,
      commentId,
      ...(leg === "dm"
        ? {
            status: { not: "SENT" as const },
            dmDeliveryUnconfirmed: false,
            attempts: { lt: MAX_COMMENT_SEND_ATTEMPTS },
          }
        : { publicReplySentAt: null, publicReplyDeliveryUnconfirmed: false }),
    },
    data:
      leg === "dm"
        ? {
            dmDeliveryUnconfirmed: true,
            attempts: { increment: 1 },
            status: "PENDING",
            errorMessage: null,
          }
        : { publicReplyDeliveryUnconfirmed: true },
  });
  return result.count === 1;
}


export async function claimUserRevealDelivery({
  automationId,
  workspaceId,
  instagramAccountId,
  userId,
  commenterName,
}: {
  automationId: string;
  workspaceId: string;
  instagramAccountId: string;
  userId: string;
  commenterName: string | null;
}): Promise<boolean> {
  const commentId = `reveal:${userId}`;

  await prisma.dmLog.upsert({
    where: {
      automationId_commentId: {
        automationId,
        commentId,
      },
    },
    create: {
      workspaceId,
      automationId,
      instagramAccountId,
      commenterId: userId,
      commenterName,
      commentText: "(follow-gated reveal)",
      commentId,
      status: "PENDING",
    },
    update: {},
  });

  const result = await prisma.dmLog.updateMany({
    where: {
      automationId,
      commentId,
      status: { not: "SENT" as const },
      dmDeliveryUnconfirmed: false,
      attempts: { lt: MAX_COMMENT_SEND_ATTEMPTS },
    },
    data: {
      dmDeliveryUnconfirmed: true,
      attempts: { increment: 1 },
      status: "PENDING",
      errorMessage: null,
    },
  });

  return result.count === 1;
}
