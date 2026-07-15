plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.15"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"
	war  // jar(단독 java -jar) + war(외부 톰캣/WAS 배포) 둘 다 산출
}

group = "com.aixnative"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	// WAR 외부 톰캣 배포 대비: 내장 톰캣을 provided 로 → bootJar/java -jar 엔 포함(단독 실행 OK),
	// 외부 톰캣에 올릴 땐 WEB-INF/lib-provided 로 빠져 컨테이너 톰캣과 충돌 안 함.
	providedRuntime("org.springframework.boot:spring-boot-starter-tomcat")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// 커뮤니티 정적 HTML 파싱(공식 RSS 부재 소스). JS 렌더 사이트는 막히면 graceful.
	implementation("org.jsoup:jsoup:1.18.3")
	// JWT (stateless auth) — jjwt 0.12.x
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	runtimeOnly("org.postgresql:postgresql")
	// GCP Cloud Run → Cloud SQL(PostgreSQL) JDBC 연결용 소켓 팩토리.
	// DB_URL 에 ?cloudSqlInstance=...&socketFactory=com.google.cloud.sql.postgres.SocketFactory 사용 시 필요.
	runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.21.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.security:spring-security-test")
	// In-memory DB for tests (PostgreSQL compatibility mode) — no live PG needed for CI/build
	testRuntimeOnly("com.h2database:h2")
	// Also available for local `bootRun` via the `h2` profile (excluded from the production jar)
	developmentOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 실행 불가한 plain war(-plain.war) 는 생략. bootWar 산출물(aixnative-*.war)이
// java -jar 단독 실행과 외부 톰캣 배포를 모두 커버하므로 그거 하나면 충분.
tasks.named("war") { enabled = false }

// `./gradlew build` 한 번에 jar(단독 java -jar) 와 war(외부 톰캣/WAS) 를 모두 산출.
tasks.named("assemble") { dependsOn("bootJar") }

// 콘솔 한글 깨짐 방지: main() / bootRun 등 JVM 실행 출력을 UTF-8 로 고정.
// (Windows 기본 콘솔 코드페이지 MS949 → UTF-8 로 통일)
tasks.withType<JavaExec>().configureEach {
	jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
