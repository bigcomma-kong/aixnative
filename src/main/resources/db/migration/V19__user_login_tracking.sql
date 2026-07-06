-- 로그인 이력 추적: 마지막 접속 시각 + 누적 로그인 횟수.
-- "가입만 하고 안 오는 사용자 vs 활성 사용자" 구분(리텐션 파악).
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;
ALTER TABLE users ADD COLUMN login_count  INT NOT NULL DEFAULT 0;
