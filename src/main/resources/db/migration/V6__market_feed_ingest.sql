-- 시장 피드 자동 수집 보강: 출처(origin) + 중복제거 키(dedup_key).
-- origin: 'ADMIN'(수동) | 'RSS:<매체>' | 'GOOGLE_NEWS' 등 — 카드 출처 배지·필터용.
-- dedup_key: 정규화된 기사 링크(프로토콜·쿼리·프래그먼트 제거, lowercase). 재수집 시 중복 삽입 차단.
-- DB-agnostic: PostgreSQL·H2 공통 구문만 사용.

ALTER TABLE market_feed_item ADD COLUMN origin VARCHAR(40);
ALTER TABLE market_feed_item ADD COLUMN dedup_key VARCHAR(300);

-- 기존(수동 등록) 행은 ADMIN 으로 표기.
UPDATE market_feed_item SET origin = 'ADMIN' WHERE origin IS NULL;

CREATE INDEX ix_mfi_dedup ON market_feed_item (dedup_key);
