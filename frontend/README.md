# aixnative 프론트엔드

aixnative SPA. **React 19 + Vite + TypeScript**. 백엔드(Spring Boot)와 **단일 컨테이너**로 배포되며, 빌드 산출물(`dist/`)은 `src/main/resources/static/` 으로 구워져 same-origin 으로 서빙된다.

## 개발
```bash
npm install        # 의존성 (package.json 변경 시)
npm run dev        # 개발 서버 (HMR, 5173). /api 호출은 8080 백엔드로 프록시
npm run build      # 타입체크(tsc -b) + 프로덕션 빌드 (dist/)
npm run lint       # oxlint
npm run preview    # 빌드 결과 미리보기
```
백엔드는 루트에서 `./gradlew bootRun`(8080). 통합 실행은 [`../docs/COMMANDS.md`](../docs/COMMANDS.md) §3.

## 구조
- `App.tsx` - 세션·탭 라우팅(시장·언더라이팅·심화분석·자산관리·마이페이지·관리자) + 페이월.
- `api.ts` - 백엔드 REST 클라이언트(타입·엔드포인트 단일 소스). API 베이스는 same-origin `/api` 기본.
- 뷰 컴포넌트: `UnderwriteView`·`DocAnalysisView`·`MarketView`/`MarketFeedView`·`MyView`/`MyDealsView`·`PropertyView`·`AdminView` 등.
- 공용: `Markdown.tsx`(AI 서술 렌더 - 심화분석 화면과 데이터 보기 모달이 공유), `ResultModal.tsx`, `Chart.tsx`/`RentBarChart.tsx`, `SocialLogin.tsx`, `Paywall.tsx`.
- `index.css` - 디자인 토큰(oklch CSS 커스텀 프로퍼티). 라이트 핀테크(인디고).

## 주의
- TS 설정에 `noUnusedLocals`/`noUnusedParameters` 활성 → 미사용 심볼은 빌드 실패. 시그니처에서 파라미터 제거 시 호출부까지 정리.
- `VITE_API_BASE`: API 를 별도 호스트로 분리할 때만 설정(`frontend/.env.production`). same-origin 배포면 비워둔다 → [`../docs/ENV.md`](../docs/ENV.md).
- 로컬 `npm run build` 가 빌드마다 다른 가짜 TS 에러를 내면 Vite dev 레이스일 수 있다. `tsc -b --force` 통과하면 소스는 정상.
