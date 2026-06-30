-- 소셜 로그인(구글/카카오/네이버) — 제공자 고유 id 저장. (auth_provider, provider_id) 로 재로그인 식별.
-- LOCAL 계정은 provider_id NULL. DB-agnostic: PostgreSQL·H2 공통 구문만 사용.
ALTER TABLE users ADD COLUMN provider_id VARCHAR(100);

-- 소셜 재로그인 조회용(부분 유니크 대신 일반 인덱스 — H2 호환). 동일 소셜계정 1행은 앱 로직이 보장.
CREATE INDEX ix_users_provider ON users (auth_provider, provider_id);
