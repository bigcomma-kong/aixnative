-- 공감랭킹 카드에 출처 유형·리스크 등급 표기(관리자 승인 판단용).
-- PostgreSQL / H2 공통 구문. 기존 행은 NEWS/LOW 기본값.
ALTER TABLE social_post ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'NEWS';
ALTER TABLE social_post ADD COLUMN risk_level  VARCHAR(10) NOT NULL DEFAULT 'LOW';
