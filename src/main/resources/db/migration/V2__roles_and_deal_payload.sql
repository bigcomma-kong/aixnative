-- 역할(USER/ADMIN) + 딜 분석 페이로드 저장.
-- DB-agnostic: PostgreSQL·H2 모두 지원하는 구문만 사용.

ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- 분석 실행에 딜명 + 입력/결과 JSON 보관(조회 API에서 반환).
ALTER TABLE ai_tool_run ADD COLUMN deal_name    VARCHAR(200);
ALTER TABLE ai_tool_run ADD COLUMN request_json TEXT;
ALTER TABLE ai_tool_run ADD COLUMN result_json  TEXT;
