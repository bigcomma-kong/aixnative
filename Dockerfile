# syntax=docker/dockerfile:1
#
# 단일 컨테이너: Vite SPA 를 빌드해 Spring 정적 리소스로 묶고, bootJar 를 JRE 런타임으로 실행.
# GCP Cloud Run 호환(컨테이너가 $PORT, 기본 8080 으로 리슨).
#
#   docker build -t aixnative .
#   docker run -p 8080:8080 -e CLAUDE_OAUTH_TOKEN=... aixnative
# Postgres 까지 한 번에: docker compose up --build

# ── Stage 1: 프론트엔드(Vite/React) 빌드 → /fe/dist ────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /fe
# 의존성 레이어 캐시: 매니페스트 먼저 복사 후 설치.
# npm ci 대신 npm install — lockfile 이 Windows 에서 생성돼 Linux 전용 optional
# 네이티브 패키지(@emnapi/*, @rollup/rollup-linux-*)의 실제 노드가 빠져 있어,
# 컨테이너(Linux)에서 lock 을 치유하며 올바른 바이너리를 설치한다.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ── Stage 2: 백엔드(Spring Boot) 빌드 — SPA 를 정적 리소스로 동봉 ──────────────
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app
# Gradle 래퍼 + 빌드 스크립트 먼저(의존성 캐시 레이어)
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon
# 소스 + 빌드된 SPA(=Spring 이 / 에서 서빙) 복사 후 jar 빌드
COPY src ./src
COPY --from=frontend /fe/dist ./src/main/resources/static
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 3: 런타임(JRE + Node, 비루트) ───────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# 공감랭킹 카드 이미지 렌더러(satori) 실행용 Node 22 설치.
# resvg 네이티브 바이너리는 glibc 를 요구 → temurin(Ubuntu/glibc) 런타임과 동일 환경에서
# npm install 해야 올바른 리눅스 바이너리(@resvg/resvg-js-linux-x64-gnu)가 깔린다.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
# 비루트 사용자로 실행(보안)
RUN groupadd -r app && useradd -r -g app app
COPY --from=backend /app/build/libs/*.jar app.jar
# 렌더러 의존성 먼저 설치(레이어 캐시). lock 은 Windows 생성이라 npm install 로 리눅스 바이너리 치유.
COPY render/package.json render/package-lock.json ./render/
RUN cd render && npm install --omit=dev --no-audit --no-fund
# 렌더 스크립트 + 한글 폰트 복사
COPY render/render-card.mjs ./render/
COPY render/fonts ./render/fonts
RUN chown -R app:app /app/render
USER app
EXPOSE 8080
ENV JAVA_OPTS=""
# 컨테이너 메모리에 맞춰 힙 자동 산정(MaxRAMPercentage). Cloud Run 은 PORT 주입.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75 $JAVA_OPTS -jar app.jar"]
