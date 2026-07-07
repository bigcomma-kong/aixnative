-- 딜 식별을 딜명(문자열) → PK(deal_id)로 전환.
-- deal_id = 딜의 anchor(첫) 런 id. 첫 분석은 자기 id를 deal_id로(self-anchor),
-- 이후 분석은 같은 deal_id에 연결. 딜 이름이 같아도 물리적으로 분리된다.
ALTER TABLE ai_tool_run ADD COLUMN deal_id BIGINT;

-- 기존 런 backfill: 같은 (tenant, owner, deal_name) 그룹의 최소 id를 anchor 로.
-- 지금의 딜명 기반 그룹핑을 그대로 보존한다. 포터블 상관 서브쿼리(PG/Oracle 공통).
UPDATE ai_tool_run t SET deal_id = (
    SELECT MIN(x.id) FROM ai_tool_run x
    WHERE x.tenant_id = t.tenant_id
      AND x.owner_user_id = t.owner_user_id
      AND x.deal_name = t.deal_name
)
WHERE t.deal_name IS NOT NULL AND t.deal_name <> '';

-- 딜명 없는 런(심층 시장 리포트 등)은 각자 독립 딜(자기 자신).
UPDATE ai_tool_run SET deal_id = id WHERE deal_id IS NULL;

CREATE INDEX ix_ai_tool_run_deal ON ai_tool_run (tenant_id, owner_user_id, deal_id);
