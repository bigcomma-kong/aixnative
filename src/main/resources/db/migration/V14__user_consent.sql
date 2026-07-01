-- 가입 동의 캡처(PIPA) — 약관·개인정보 동의 일시·버전, 마케팅 수신동의(선택)를 사용자별로 기록.
-- 기존 행은 NULL(레거시 가입 — 동의 시점 미상). DB-agnostic.

ALTER TABLE users ADD COLUMN terms_agreed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN marketing_opt_in BOOLEAN DEFAULT FALSE NOT NULL;
