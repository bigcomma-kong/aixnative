package com.aixnative.common.config

import com.aixnative.common.security.JwtAuthFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Stateless JWT security (Spring Security 6). Public: auth endpoints, health,
 * error. Everything else requires a valid token. CSRF off (no cookies/session).
 */
@Configuration
class SecurityConfig(private val jwtAuthFilter: JwtAuthFilter) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            // CORS: same-origin 배포면 비활성과 동일, 다른 호스트(SPA↔API)면 허용 origin 적용.
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/auth/**",
                    // 시장 데이터 자동 수집 트리거(Cloud Scheduler). 공개 경로지만 컨트롤러에서 공유 토큰 검증.
                    "/api/ingest/**",
                    // 뉴스레터 메일 푸터의 1클릭 해지 링크(토큰 기반).
                    "/api/newsletter/unsubscribe",
                    // 읽기전용 공유 보고서(토큰 기반, 무인증 열람).
                    "/api/public/**",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/error",
                ).permitAll()
                // 단일 컨테이너 배포: Spring 이 Vite SPA(index.html + /assets/**)를 서빙한다.
                // SPA 셸·정적 자산은 공개(GET). 보호 대상은 /api/** (인증) 뿐.
                it.requestMatchers(
                    HttpMethod.GET,
                    "/", "/index.html", "/assets/**",
                    "/*.svg", "/*.ico", "/*.png", "/*.json", "/*.txt", "/*.webmanifest",
                ).permitAll()
                it.requestMatchers("/api/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * CORS for the SPA. Origins from `app.allowed-origins` (CSV), default = aixnative.com.
     * JWT is sent via the Authorization header (no cookies), so credentials stay off.
     */
    @Bean
    fun corsConfigurationSource(
        @Value("\${app.allowed-origins:https://aixnative.com,https://www.aixnative.com}") allowedOrigins: String,
    ): CorsConfigurationSource {
        val origins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val config = CorsConfiguration().apply {
            this.allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            allowCredentials = false
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
