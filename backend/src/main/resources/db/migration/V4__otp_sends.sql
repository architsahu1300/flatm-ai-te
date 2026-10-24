-- V4__otp_sends.sql — one row per sign-in code the app asked a provider to send.
--
-- The daily send cap counts these rows instead of an in-memory counter, because it is
-- the control that protects real SMS spend and Render's free tier restarts the backend
-- on every wake. Deliberately no phone number or IP: the count is all the cap needs.

CREATE TABLE otp_sends (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider   varchar(30) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_otp_sends_created ON otp_sends (created_at);
