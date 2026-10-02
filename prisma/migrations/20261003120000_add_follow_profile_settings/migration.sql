-- Add configurable profile link/button settings for the follow gate.
ALTER TABLE "Automation"
  ADD COLUMN "followPromptProfileButtonLabel" TEXT,
  ADD COLUMN "followPromptProfileUrl" TEXT;
