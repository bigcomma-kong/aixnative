# 환경변수 — underwrite-ai

> `.gitignore` 가 `.env.*` 를 무시하므로 `.env.example` 대신 이 문서로 관리합니다.
> 모든 시크릿은 **신규 발급** 후 환경변수로 주입합니다. (기존 MASTERN 값 복사 금지 — `docs/API-KEYS.md`)

## Phase 1 (핵심 골격) 필수
| ENV | 기본값(dev fallback) | 용도 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/underwriteai` | dev PostgreSQL 접속 URL |
| `DB_USER` | `underwriteai` | DB 사용자 |
| `DB_PASSWORD` | (빈 값) | DB 비밀번호 |
| `JWT_SECRET` | dev 전용 더미(32바이트+) | JWT HS256 서명 키. **운영 필수 교체** |
| `JWT_ACCESS_TTL_MINUTES` | `120` | access 토큰 만료(분) |
| `FREE_SIGNUP_CREDITS` | `5` | 가입 시 무료 부여 크레딧 수 |

## AI (선택 — 미설정 시 Claude provider `isConfigured=false`)
| ENV | 기본값 | 용도 |
|---|---|---|
| `CLAUDE_API_KEY` | (빈 값) | Anthropic API 키(신규) |
| `CLAUDE_API_URL` | `https://api.anthropic.com/v1/messages` | 엔드포인트 |
| `CLAUDE_API_MODEL` | `claude-opus-4-8` | 모델 |
| `CLAUDE_MAX_TOKENS` | `4096` | 최대 토큰 |
| `CLAUDE_PRIORITY` | `0` | 라우터 우선순위(낮을수록 우선) |
| `AI_AUTO_FALLBACK` | `true` | provider 폴백 on/off |
| `AI_PROVIDER_TIMEOUT_MS` | `75000` | provider 단건 타임아웃 |
| `AI_OVERALL_DEADLINE_MS` | `90000` | AI 호출 전체 deadline |

## 범위 밖(후속 — 키 확보 후 application.yml 주석 해제)
- 이메일 발송: `MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD`
- 소셜 로그인: `GOOGLE_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`

## 로컬 실행 예 (PowerShell)
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/underwriteai"
$env:DB_USER="underwriteai"; $env:DB_PASSWORD="secret"
$env:JWT_SECRET="<32바이트 이상 랜덤 문자열>"
./gradlew bootRun
```

테스트(`./gradlew build`)는 H2 인메모리(test 프로파일)를 쓰므로 **위 ENV 없이도** 그린입니다.
