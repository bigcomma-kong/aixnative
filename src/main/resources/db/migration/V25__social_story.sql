-- 공감랭킹 스토리 모드: 커뮤니티 핫글 1건 = 스토리 게시물 1건(장면별 AI 이미지 캐러셀).
-- 기존 랭킹 게시물과 공존(kind). PostgreSQL / H2 공통. 기존 행은 RANKING 하위호환.
ALTER TABLE social_post ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'RANKING';
ALTER TABLE social_post ADD COLUMN engagement   VARCHAR(120);  -- "👍94만 💬3.7천"
ALTER TABLE social_post ADD COLUMN source_board VARCHAR(120);  -- "에펨코리아 포텐 터짐 게시판"
