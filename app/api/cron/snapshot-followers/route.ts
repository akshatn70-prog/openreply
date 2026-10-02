import { NextResponse } from "next/server";
import { prisma } from "@/lib/db/client";
import { createInstagramContext } from "@/lib/instagram/context";
import { getUserInfo } from "@/lib/instagram/read-content";
import { recordFollowerSnapshot, backfillFollowerHistory } from "@/lib/reports/follower-history";

export async function GET(request: Request) {
  const cronSecret = process.env.CRON_SECRET;
  const authHeader = request.headers.get("authorization");

  if (!cronSecret || authHeader !== `Bearer ${cronSecret}`) {
    return NextResponse.json(
      { success: false, error: "Unauthorized" },
      { status: 401 }
    );
  }

  const accounts = await prisma.instagramAccount.findMany({
    where: {
      accessToken: { not: "" },
    },
    select: {
      id: true,
      workspaceId: true,
      username: true,
      instagramId: true,
      accessToken: true,
    },
  });

  let recorded = 0;
  let backfilled = 0;
  const failures: Array<{ username: string; reason: string }> = [];

  for (const account of accounts) {
    try {
      const token = await createInstagramContext(account);
      const info = await getUserInfo({ context: token });

      if (typeof info.followers_count !== "number") {
        failures.push({
          username: account.username,
          reason: "followers_count not returned",
        });
        continue;
      }

      await recordFollowerSnapshot(account.id, info.followers_count);
      recorded += 1;

      // First time we see this account, try to recover the last 30 days.
      const existing = await prisma.followerSnapshot.count({
        where: { instagramAccountId: account.id },
      });

      if (existing <= 1) {
        backfilled += await backfillFollowerHistory({
          instagramAccountId: account.id,
          accessToken: token,
          instagramId: account.instagramId,
          currentFollowers: info.followers_count,
        });
      }
    } catch (err) {
      const reason = err instanceof Error ? err.message : "Unknown error";
      failures.push({ username: account.username, reason });

      await prisma.operationalEvent
        .create({
          data: {
            source: "SYSTEM",
            level: "WARNING",
            workspaceId: account.workspaceId,
            message: "Follower snapshot failed",
            payload: { username: account.username, reason },
          },
        })
        .catch(() => {});
    }
  }

  return NextResponse.json({
    success: true,
    data: {
      accounts: accounts.length,
      recorded,
      backfilled,
      failures,
    },
  });
}

