import { NextRequest, NextResponse } from "next/server";
import { revokeMobileToken } from "@/lib/mobile-auth";

export async function POST(request: NextRequest) {
  const authorization = request.headers.get("authorization");
  if (authorization?.startsWith("Bearer ")) {
    await revokeMobileToken(authorization.slice(7).trim());
  }
  return NextResponse.json({ success: true });
}
