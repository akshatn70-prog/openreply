import { NextRequest, NextResponse } from "next/server";
import { exchangeMobileAuthCode } from "@/lib/mobile-auth";
import { prisma } from "@/lib/db/client";

export async function POST(request: NextRequest) {
  const body = await request.json().catch(() => ({}));
  const code = typeof body.code === "string" ? body.code.trim() : "";
  if (!code) return NextResponse.json({ success: false, error: "Missing code" }, { status: 400 });

  const exchanged = await exchangeMobileAuthCode(code);
  if (!exchanged) {
    return NextResponse.json({ success: false, error: "Invalid or expired mobile login code" }, { status: 401 });
  }

  const user = await prisma.user.findUnique({
    where: { id: exchanged.userId },
    select: { id: true, name: true, email: true, image: true },
  });
  if (!user) return NextResponse.json({ success: false, error: "User not found" }, { status: 401 });

  return NextResponse.json({ success: true, data: { token: exchanged.token, user } });
}
