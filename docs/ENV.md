# 환경변수 — aixnative

> `.gitignore` 가 `.env.*` 를 무시하므로 `.env.example` 대신 이 문서로 관리합니다.
> 모든 시크릿은 **신규 발급** 후 환경변수로 주입합니다. (기존 MASTERN 값 복사 금지 — `docs/API-KEYS.md`)

## Phase 1 (핵심 골격) 필수
| ENV | 기본값(dev fallback) | 용도 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/aixnative` | dev PostgreSQL 접속 URL |
| `DB_USER` | `aixnative` | DB 사용자 |
| `DB_PASSWORD` | (빈 값) | DB 비밀번호 |
| `JWT_SECRET` | dev 전용 더미(32바이트+) | JWT HS256 서명 키. **운영 필수 교체** |
| `JWT_ACCESS_TTL_MINUTES` | `120` | access 토큰 만료(분) |
| `FREE_SIGNUP_CREDITS` | `5` | 가입 시 무료 부여 크레딧 수 |

## AI (선택 — 미설정 시 Claude provider `isConfigured=false`)
> 인증은 둘 중 하나. **OAuth 토큰이 설정되면 그쪽이 우선**(Bearer + oauth beta 헤더), 아니면 API 키(x-api-key).

| ENV | 기본값 | 용도 |
|---|---|---|
| `CLAUDE_OAUTH_TOKEN` | (빈 값) | 구독 OAuth 토큰(`sk-ant-oat...`, `claude setup-token` 발급). 설정 시 Bearer 인증 |
| `CLAUDE_API_KEY` | (빈 값) | Anthropic API 키(`sk-ant-api...`, 신규). OAuth 미설정 시 사용 |
| `CLAUDE_API_URL` | `https://api.anthropic.com/v1/messages` | 엔드포인트 |
| `CLAUDE_API_MODEL` | `claude-opus-4-8` | 모델 |
| `CLAUDE_MAX_TOKENS` | `4096` | 최대 토큰 |
| `CLAUDE_PRIORITY` | `0` | 라우터 우선순위(낮을수록 우선) |
| `AI_AUTO_FALLBACK` | `true` | provider 폴백 on/off |
| `AI_PROVIDER_TIMEOUT_MS` | `75000` | provider 단건 타임아웃 |
| `AI_OVERALL_DEADLINE_MS` | `90000` | AI 호출 전체 deadline |

## 실측 시장데이터 (선택 — 미설정 시 graceful degrade, AI 추정 모드 유지)
전부 **무료 공공 API**. 키 미설정이어도 분석은 동작하며, 설정 시 시장조사·심화리서치가
추정 대신 **실측 인용**으로 바뀌고 신뢰도가 올라갑니다. 🔐 **MASTERN 키 복사 금지 — 전부 신규 발급.**

| ENV | 발급처 | 용도 |
|---|---|---|
| `ECOS_API_KEY` | [ecos.bok.or.kr](https://ecos.bok.or.kr) (한국은행) | 기준금리·국고채 3y/10y → `[실측 매크로]` |
| `REB_RONE_API_KEY` | [reb.or.kr R-ONE](https://www.reb.or.kr/r-one) (한국부동산원) | 권역 공실률·임대료·수익률 → `[실측 임대시장]` (오피스·리테일만) |
| `DATA_GO_KR_API_KEY` | [data.go.kr](https://www.data.go.kr) (국토부 RTMS) | 상업업무용·토지 거래 comps → `[실거래가]`; 거래상대방 실사(사업자상태·제재·기업정보·연금) |
| `VWORLD_API_KEY` | [api.vworld.kr](https://www.vworld.kr/dev/v4api.do) (국토정보) | **용도지역**(req/data LT_C_UQ111) + **개별공시지가**(ned/data) → `[실측 용도지역·공시지가]` (개발·세무). |
| `VWORLD_DOMAIN` | (V-World 키에 등록한 운영 도메인, 예: `aixnative.com`) | ⚠️ **필수.** V-World 키는 등록 도메인에 묶여 있고, 서버 호출은 Referer 가 없어 이 값을 `domain` 파라미터로 보내야 인증 통과. 미설정 시 `ned/data`·일부 `req/data` 가 `INCORRECT_KEY`. (별도 NED 활용신청·data.go.kr 불필요 — 도메인만 맞으면 동작.) |
| `VWORLD_NED_ENABLED` | (true/false, 기본 false) | **개별공시지가**(`ned/data`) 호출 on/off. `VWORLD_API_KEY`+`VWORLD_DOMAIN`+`JUSO_API_KEY`(PNU용) 갖춰지면 `true`. 미설정 시 공시지가 줄만 생략, 용도지역은 정상. |
| `JUSO_API_KEY` | [business.juso.go.kr](https://business.juso.go.kr) (도로명주소) | 주소→법정동코드+번지(PNU 조립용 지오코더, V-World 전제) |

> 각 소스 독립(하나만 넣어도 그 부분 동작). 주소→시군구코드(5)는 **내장 표(`LawdCode`)** — Kakao 불필요. **용도지역**은 `VWORLD_API_KEY` + `VWORLD_DOMAIN` 으로 동작(V-World 자체 지오코더 — juso 불필요). **개별공시지가**는 PNU(19)가 필요해 `JUSO_API_KEY` + `VWORLD_NED_ENABLED=true` 추가. **공통 전제 = `VWORLD_DOMAIN`**(키 등록 도메인) 일치 — 이게 빠지면 전부 INCORRECT_KEY.
>
> **거래상대방 실사**: 일부 API(국세청 사업자상태·조달청 제재·국민연금)는 `DATA_GO_KR_API_KEY` 로 즉시 동작하지만, **금융위 기업기본정보(1160100)** 는 data.go.kr 에서 별도 활용신청·승인이 필요(미승인 시 해당 항목만 생략).

## 도메인 / 배포 (운영 = aixnative.com)
| ENV | 기본값 | 용도 |
|---|---|---|
| `ALLOWED_ORIGINS` | `https://aixnative.com,https://www.aixnative.com,http://localhost:5173` | SPA CORS 허용 origin(CSV). same-origin 배포면 사실상 무관 |
| `ADMIN_EMAIL` | `admin@aixnative.com` | 이 이메일로 가입 시 ADMIN 승격 |
| `VITE_API_BASE` (프론트 빌드시) | (빈 값 = same-origin `/api`) | API 가 별도 호스트일 때만. 예: `https://api.aixnative.com` (끝에 `/api` 안 붙임). `frontend/.env.production` 에 설정 |

> same-origin 배포(SPA + API 모두 aixnative.com, API 는 `/api`)면 `VITE_API_BASE` 비워두고 `ALLOWED_ORIGINS` 도 신경 쓸 필요 없습니다. API 를 `api.aixnative.com` 으로 분리할 때만 둘 다 설정하세요.

## 이메일 인증/발송 (SMTP) — 어뷰징 방어 1단계
가입 시 무료 크레딧은 **이메일 인증 후** 지급(미인증=크레딧 0). 인증 메일은 SMTP 로 발송한다.
앱은 순수 SMTP 클라이언트(`JavaMailSender`)라 **어떤 SMTP 서버든** 값만 바꾸면 동작(코드 변경 0).

> ⚠️ **Cloud Run 은 아웃바운드 25번 포트 차단** → 반드시 **587(STARTTLS)** 또는 **465(implicit TLS)** 인증 릴레이 사용.
> `deploy/deploy.sh` 는 587/STARTTLS 로 배선. 465 사용 시 `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true`(+포트 465) 로 교체.

| ENV | 예시 | 용도 |
|---|---|---|
| `SPRING_MAIL_HOST` | `smtp.gmail.com` | SMTP 호스트 (미설정 시 발송 안 하고 **인증 링크를 로그로 출력**) |
| `SPRING_MAIL_PORT` | `587` | 587(STARTTLS) 또는 465(SSL) |
| `SPRING_MAIL_USERNAME` | (Gmail 주소) | SMTP 계정 |
| `SPRING_MAIL_PASSWORD` | (앱 비밀번호) | **시크릿** — Secret Manager `SPRING_MAIL_PASSWORD` 로 주입 |
| `MAIL_FROM` | `admin@aixnative.com` | 발신 표기. 인증 계정과 다르면 Gmail 이 계정 주소로 재작성 → **발신 계정과 일치시킬 것** |
| `APP_BASE_URL` | `https://aixnative-…run.app` | 인증/재설정 링크 베이스 URL(도메인 연결 후 교체) |
| `VERIFICATION_TTL_HOURS` | `24` | 이메일 인증 토큰 만료(시간) |
| `PASSWORD_RESET_TTL_HOURS` | `2` | 비밀번호 재설정 토큰 만료(시간, 1회용) |
| `SIGNUP_RATE_LIMIT_PER_HOUR` | `5` | IP당 가입/재발송/비번찾기 시간 한도 |

> **비밀번호 찾기**: 가입 이메일로 `${APP_BASE_URL}/?reset=<token>` 링크 발송 → SPA 가 재설정 화면 표시 → `POST /api/auth/reset-password`. 같은 SMTP 설정을 재사용한다(별도 키 불필요).

### 현재 선택 (이력)
- **2026-06-29 — 개인 Gmail 앱 비밀번호** 채택(초기 무료·저용량 ~수백통/일). `smtp.gmail.com:587`.
  - 전제: Google 계정 **2단계 인증 ON** → [앱 비밀번호](https://myaccount.google.com/apppasswords) 발급(16자).
  - 라이브 발신 계정 = `bigcomma16@gmail.com`. 따라서 `MAIL_FROM` 도 동일 주소 유지(스팸 회피). aixnative.com SPF 에 Google 미포함이라 `admin@aixnative.com` 으로 보내면 도달률 저하 위험.
- **예정 — Google Workspace(`admin@aixnative.com`)** 로 전환:
  - 진짜 계정이라 별칭 불필요 → 인증 주체=발신 주소 일치. DKIM/SPF 정식 서명(도달률 최상).
  - 전환 시 env 만: `MAIL_USERNAME=admin@aixnative.com` + 그 계정 앱 비밀번호(`SPRING_MAIL_PASSWORD`) + `MAIL_FROM=admin@aixnative.com`.
  - 도메인은 Cloudflare DNS — Workspace MX/SPF/DKIM 레코드를 Cloudflare 에 추가(Email Routing 은 끄거나 대체).
- **추후 교체 가능(코드 변경 없음)** — 더 큰 볼륨/도달률 필요 시 **AWS SES·Brevo·Mailgun·본인 SMTP 서버** 등으로:
  1. (있으면) 발신 도메인 SPF/DKIM 설정
  2. `gcloud secrets versions add SPRING_MAIL_PASSWORD --data-file=<새키파일>`
  3. `MAIL_HOST/PORT/USERNAME/FROM` env 만 바꿔 `deploy/deploy.sh` 재실행(또는 `gcloud run services update`)
  - 자세한 배포 절차: `.claude/skills/aixnative-deploy/SKILL.md`

## 범위 밖(후속 — 키 확보 후 application.yml 주석 해제)
- 소셜 로그인: `GOOGLE_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`

## 로컬 실행 예 (PowerShell)
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/aixnative"
$env:DB_USER="aixnative"; $env:DB_PASSWORD="secret"
$env:JWT_SECRET="<32바이트 이상 랜덤 문자열>"
./gradlew bootRun
```

테스트(`./gradlew build`)는 H2 인메모리(test 프로파일)를 쓰므로 **위 ENV 없이도** 그린입니다.
