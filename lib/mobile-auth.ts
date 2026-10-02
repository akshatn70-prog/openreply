import { createHash, randomBytes } from "node:crypto";
import { prisma } from "@/lib/db/client";

const CODE_TTL_MS = 10 * 60 * 1000;
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;

export const MOBILE_REDIRECT_URI = "thehomeodoc://auth";

function hash(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

export function createMobileCode() {
  const raw = randomBytes(32).toString("base64url");
  return { raw, hash: hash(raw) };
}

export async function createMobileAuthCode(userId: string) {
  const { raw, hash: codeHash } = createMobileCode();
  await prisma.mobileAuthCode.deleteMany({
    where: { userId, expiresAt: { lt: new Date() } },
  });
  await prisma.mobileAuthCode.create({
    data: {
      userId,
      codeHash,
      expiresAt: new Date(Date.now() + CODE_TTL_MS),
    },
  });
  return raw;
}

export async function exchangeMobileAuthCode(code: string) {
  const record = await prisma.mobileAuthCode.findUnique({
    where: { codeHash: hash(code) },
  });
  if (!record || record.usedAt || record.expiresAt <= new Date()) return null;

  const consumed = await prisma.mobileAuthCode.updateMany({
    where: { id: record.id, usedAt: null, expiresAt: { gt: new Date() } },
    data: { usedAt: new Date() },
  });
  if (consumed.count !== 1) return null;

  const rawToken = randomBytes(48).toString("base64url");
  await prisma.mobileSession.create({
    data: {
      userId: record.userId,
      tokenHash: hash(rawToken),
      expiresAt: new Date(Date.now() + SESSION_TTL_MS),
    },
  });
  return { token: rawToken, userId: record.userId };
}

export async function getMobileUserId(token: string | null | undefined) {
  if (!token) return null;
  const session = await prisma.mobileSession.findUnique({
    where: { tokenHash: hash(token) },
    select: { userId: true, expiresAt: true, revokedAt: true },
  });
  if (!session || session.revokedAt || session.expiresAt <= new Date()) return null;
  return session.userId;
}

export async function revokeMobileToken(token: string) {
  await prisma.mobileSession.updateMany({
    where: { tokenHash: hash(token) },
    data: { revokedAt: new Date() },
  });
}
