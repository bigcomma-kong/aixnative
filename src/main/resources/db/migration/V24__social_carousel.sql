-- 공감랭킹 캐러셀 개편: 게시물당 슬라이드 여러 장(base64 PNG) 저장.
-- 기존 image_base64(표지 1장)는 유지하고, images_json 에 전체 슬라이드 배열을 보관.
-- PostgreSQL / H2 공통. 기존 행은 NULL(단일 이미지 흐름 하위호환).
ALTER TABLE social_post ADD COLUMN images_json TEXT;
