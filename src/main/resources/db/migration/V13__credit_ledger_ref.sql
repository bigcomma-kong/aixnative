-- 크레딧 원장에 "출처/경로" 참조 추가 — 충전(PURCHASE) 시 결제수단·금액, 관리자 조정 시 관리자 식별 등.
-- 사용 현황·관리자 화면에서 "어떤 경로로 충전/변동됐는지" 표시용. 기존 행은 NULL(출처 미상).
-- DB-agnostic: PostgreSQL·H2 공통 구문만 사용.

ALTER TABLE credit_ledger ADD COLUMN ref VARCHAR(200);
