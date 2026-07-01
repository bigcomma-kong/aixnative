-- 읽기전용 공유 링크 — 분석 런에 공유 토큰 부여. 토큰으로 무인증 공개 보고서 조회.
-- 대부분 NULL(공유 안 함). 발급된 것만 유니크. DB-agnostic(PostgreSQL·H2 공통).

ALTER TABLE ai_tool_run ADD COLUMN share_token VARCHAR(64);
CREATE UNIQUE INDEX ux_ai_tool_run_share_token ON ai_tool_run (share_token);
