# API-KEYS — 발급 목록 (값 없음 · 전부 신규 발급)

> 🔐 **기존 MASTERN 키 값 복사 금지.** 전부 신규 발급 후 **환경변수**(`${ENV:fallback}`)로 주입.
> ⚠ = **상업 이용 약관 확인 필수**(유료 상품 노출 가능 여부).

## v1 (hero 언더라이팅) 최소 세트
| API | 설정 키(이름) | 용도 | 비고 |
|---|---|---|---|
| **Anthropic Claude** | `claude.api.key` `claude.api.model` | 언더라이팅 내러티브·스크리닝·리스크 | 유료·필수 |
| **이메일 발송** | `spring.mail.*` (또는 SES/SendGrid SDK 키) | 가입 인증·알림 | 🆕 상업용 |
| (선택) Mistral/Cohere/Groq | `mistral.api.key` `cohere.api.key` `groq.api.key` | 저가/무료 폴백 | 무료티어 |

> 언더라이팅의 ProForma 계산은 **순수 로직(외부 API 0)** 이라, v1 은 사실상 **Claude + 이메일**만으로 출시 가능.

## Phase 4 이후 (인접 기능 보강 시)
| API | 설정 키(이름) | 용도 | 비고 |
|---|---|---|---|
| OpenDART | `dart.api.key` | 공시·재무 | ⚠ 재배포 약관 |
| data.go.kr (단일 키 다용도) | `data.go.kr.api.key` | RTMS 실거래가·온비드·사업자실사4종·LURIS·건축물대장 | ⚠ 모듈별 활용신청·상업여부 |
| 온비드 | `onbid.api.key` | 공매 딜 | ⚠ 승인 |
| ECOS 한국은행 | `ecos.api.key` | 금리·매크로(언더라이팅 금리 컨텍스트) | ⚠ 출처표기 |
| R-ONE 한국부동산원 | `reb.rone.api.key` | 부동산 통계 | ⚠ |
| Kakao | `kakao.rest.api.key` `kakao.maps.api.key` | 지도·지오코딩 | IP/도메인 제한 |
| VWorld | `vworld.api.key` | 지오코딩 백업·공간정보 | 도메인 제한 |
| 법제처 LAW | `law.api.oc` | 법령(규제) | IP 바인딩 |
| Naver 뉴스 | `naver.api.client-id/secret` | 딜 뉴스 | |
| (선택) DeepL | `deepl.api.key` | 번역 | |
| (나중) 결제 PG | — | 포트원/토스 | Phase 5 |

## 소셜 로그인 (인증)
| | 설정 키(이름) | 비고 |
|---|---|---|
| Google OAuth2 | `spring.security.oauth2.client.registration.google.*` | client-id/secret 신규 |
| Kakao OAuth2 | `...kakao.*` | client-id/secret 신규 |

## ⚠ 상업 약관 — Phase 4 전 반드시 확인
data.go.kr / OpenDART / ECOS / R-ONE 데이터를 **유료 상품에 노출**하는 게 약관상 허용되는지. 불가 시 해당 보강 기능 제외 또는 상업용 키 별도. **hero(언더라이팅)는 이 리스크에서 자유로움**(Claude만).
