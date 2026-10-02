-- OpenReply is now Meta-only. Remove the optional third-party Zernio integration.
-- This intentionally removes any Zernio-only connection metadata.
DROP TABLE IF EXISTS "ZernioConnection";
ALTER TABLE "InstagramAccount" DROP COLUMN IF EXISTS "zernioAccountId";
ALTER TABLE "InstagramAccount" DROP COLUMN IF EXISTS "provider";
DROP TYPE IF EXISTS "InstagramProvider";
