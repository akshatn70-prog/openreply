import { redirect } from "next/navigation";
import { auth } from "@/lib/auth";
import { createMobileAuthCode, MOBILE_REDIRECT_URI } from "@/lib/mobile-auth";

export const dynamic = "force-dynamic";

export default async function MobileAuthComplete() {
  const session = await auth();
  if (!session?.user?.id) {
    redirect("/login?callbackUrl=%2Fmobile-auth-complete");
  }

  const code = await createMobileAuthCode(session.user.id);
  redirect(
    `${MOBILE_REDIRECT_URI}?code=${encodeURIComponent(code)}`
  );
}
