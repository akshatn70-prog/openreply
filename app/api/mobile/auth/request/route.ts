import { NextRequest, NextResponse } from "next/server";
import { signIn, EMAIL_PROVIDER_ID } from "@/lib/auth";
import { isEmailAllowedToSignIn } from "@/lib/env";
import { z } from "zod";

const bodySchema = z.object({
  email: z.string().email(),
});

export async function POST(request: NextRequest) {
  const body = await request.json().catch(() => null);
  const parsed = bodySchema.safeParse(body);

  if (!parsed.success) {
    return NextResponse.json(
      { success: false, error: "Enter a valid email address." },
      { status: 400 }
    );
  }

  const email = parsed.data.email.trim().toLowerCase();

  if (!isEmailAllowedToSignIn(email)) {
    return NextResponse.json(
      { success: false, error: "This email address is not allowed to sign in." },
      { status: 403 }
    );
  }

  try {
    await signIn(EMAIL_PROVIDER_ID, {
      email,
      redirectTo: "/api/mobile/auth/callback",
    });
  } catch (error) {
    const digest = error instanceof Error
      ? (error as Error & { digest?: string }).digest
      : undefined;

    // Auth.js server-side signIn completes the request by throwing its
    // internal redirect. The email has already been sent at this point.
    if (digest?.startsWith("NEXT_REDIRECT")) {
      return NextResponse.json({ success: true });
    }

    console.error("[Mobile Auth] Failed to send sign-in link:", error);
    return NextResponse.json(
      { success: false, error: "Unable to send the sign-in link right now." },
      { status: 500 }
    );
  }

  return NextResponse.json({ success: true });
}
