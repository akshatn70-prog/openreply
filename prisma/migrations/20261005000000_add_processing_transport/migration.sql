-- Additive only: existing campaigns stay on the current Supabase queue.
CREATE TYPE "ProcessingTransport" AS ENUM ('SUPABASE', 'TELEGRAM');

ALTER TABLE "Automation"
  ADD COLUMN "processingTransport" "ProcessingTransport" NOT NULL DEFAULT 'SUPABASE';

CREATE INDEX "Automation_processingTransport_idx"
  ON "Automation"("processingTransport");
