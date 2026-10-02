import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth";
import { createMobileAuthCode, MOBILE_REDIRECT_URI } from "@/lib/mobile-auth";

export const dynamic = "force-dynamic";

export async function GET(_request: NextRequest) {
  const session = await auth();

  if (!session?.user?.id) {
    return NextResponse.redirect(new URL("/login", process.env.NEXTAUTH_URL));
  }

  const code = await createMobileAuthCode(session.user.id);

  return NextResponse.redirect(
    `${MOBILE_REDIRECT_URI}?code=${encodeURIComponent(code)}`
  );
}
