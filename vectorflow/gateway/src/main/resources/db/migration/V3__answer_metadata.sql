-- Phase 2: remember where each answer came from and which model wrote it.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS answer_type VARCHAR(20);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS model VARCHAR(60);
