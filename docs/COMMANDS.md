# 자주 쓰는 명령어 (빌드 · 소스 · 배포)

> IntelliJ 터미널이 **PowerShell**이면 `.\gradlew.bat`(또는 `.\gradlew`), **Git Bash**면 `./gradlew`.
> 아래는 Git Bash(`./gradlew`) 기준 — PowerShell은 `./` → `.\` 로 바꾸면 됩니다.

## 1. 백엔드 (Gradle) — 루트 폴더에서
```bash
./gradlew compileKotlin      # 코틀린 컴파일만 (제일 빠른 문법/타입 체크)
./gradlew test               # 테스트 실행
./gradlew bootRun            # 앱 로컬 실행 (기본 h2 프로필 → 무설치 부팅, 8080)
./gradlew bootJar            # 실행 가능한 단일 jar 생성 (build/libs/aixnative-*.jar)
./gradlew build              # 컴파일 + 테스트 + jar 전체
./gradlew clean              # build/ 산출물 삭제 (옛 jar 잔재 정리)
./gradlew clean build        # 깨끗하게 처음부터 빌드
./gradlew compileKotlin -q   # 조용히 (에러만 출력)
```
흐름: 수정 → `./gradlew compileKotlin` 로 빠르게 확인 → 필요시 `bootRun`.

## 2. 프론트엔드 (Vite) — `frontend/` 폴더에서
```bash
cd frontend
npm install        # 의존성 설치 (package.json 바뀌었을 때)
npm run dev        # 개발 서버 (HMR, 보통 5173 · /api 는 8080 프록시)
npm run build      # 타입체크(tsc) + 프로덕션 빌드 (dist/)
npm run lint       # oxlint 검사
npm run preview    # 빌드 결과 미리보기
```

## 3. 로컬 통합 실행 (개발 중) — 터미널 2개
```bash
# 터미널 A (루트): 백엔드
./gradlew bootRun
# 터미널 B (frontend/): 프론트 — /api 호출은 자동으로 8080 프록시
cd frontend && npm run dev
```
→ 브라우저 `http://localhost:5173`

## 4. Git
```bash
git status
git add -A && git commit -m "feat: ..."   # type: feat/fix/refactor/docs/chore
git log --oneline -10
git diff                                   # 작업 트리 변경
git restore <file>                         # 특정 파일 변경 되돌리기
```

## 5. 배포 (GCP) — 코드만 바뀐 안전 재배포
> ⚠️ `deploy/deploy.sh` 통째 실행은 **피한다**(빈 시크릿이면 JWT/DB 비번을 새로 생성 → 전체 로그아웃,
> DB 비번 리셋). 아래처럼 **이미지 빌드 + run deploy** 만 하면 기존 env·시크릿이 보존된다.

gcloud이 PATH에 없으면 PowerShell에서 먼저:
```powershell
$env:Path += ";C:\Users\User\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin"
```
그다음 (PowerShell):
```powershell
# (1) 프론트 빌드
cd frontend; npm run build; cd ..
# (2) 이미지 빌드 (Cloud Build — 로컬 Docker 불필요)
gcloud builds submit --tag asia-northeast3-docker.pkg.dev/aixnative/aixnative/aixnative:v1 .
# (3) 새 리비전 배포 — 기존 env·시크릿 보존 (env 는 --set-env-vars 가 전체 교체하므로 SMTP 포함 매번 재주입)
$envArg = '^|^SPRING_PROFILES_ACTIVE=postgres|DB_URL=jdbc:postgresql:///aixnative?cloudSqlInstance=aixnative:asia-northeast3:aixnative-pg&socketFactory=com.google.cloud.sql.postgres.SocketFactory|DB_USER=aixnative|ALLOWED_ORIGINS=https://aixnative.com,https://www.aixnative.com,https://aixnative-310112011265.asia-northeast3.run.app,http://localhost:5173|APP_BASE_URL=https://www.aixnative.com|MAIL_FROM=bigcomma16@gmail.com|SPRING_MAIL_HOST=smtp.gmail.com|SPRING_MAIL_PORT=587|SPRING_MAIL_USERNAME=bigcomma16@gmail.com|SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true|SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true'
$secretArg = 'DB_PASSWORD=DB_PASSWORD:latest,JWT_SECRET=JWT_SECRET:latest,CLAUDE_OAUTH_TOKEN=CLAUDE_OAUTH_TOKEN:latest,SPRING_MAIL_PASSWORD=SPRING_MAIL_PASSWORD:latest'
gcloud run deploy aixnative --image=asia-northeast3-docker.pkg.dev/aixnative/aixnative/aixnative:v1 --region=asia-northeast3 --platform=managed --allow-unauthenticated --add-cloudsql-instances=aixnative:asia-northeast3:aixnative-pg --set-env-vars=$envArg --set-secrets=$secretArg --cpu=1 --memory=1Gi --min-instances=0 --max-instances=4 --port=8080
```
배포 후 스모크: `https://www.aixnative.com/actuator/health` → `{"status":"UP"}`.

> Workspace 메일 전환 시 `MAIL_USERNAME`/`MAIL_FROM` 을 `admin@aixnative.com` 으로 바꾸고
> `SPRING_MAIL_PASSWORD` 시크릿을 그 계정 앱비번으로 갱신 → 자세한 건 `docs/ENV.md`.

## 6. 자주 쓰는 조합
```bash
./gradlew clean build                       # 백엔드 깨끗이 전체 빌드
cd frontend && npm run build && cd ..        # 프론트 빌드
./gradlew bootRun                            # 로컬 실행
git add -A && git commit -m "fix: ..."       # 커밋
```

---
**빠른 점검**: 배포 전 `./gradlew compileKotlin`(백엔드) + `npm run build`(프론트) 둘 다 그린이면 거의 안전.
