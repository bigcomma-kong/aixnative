package com.aixnative.social.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 소셜 수집을 백그라운드로 실행 - 관리자/스케줄러 트리거가 즉시 반환하도록(브라우저 524·요청 타임아웃 회피).
 * 커뮤니티 스크래핑 + Claude 각색 + 이미지 + 렌더가 분 단위라 동기로는 HTTP 타임아웃에 걸린다.
 *
 * 단일 스레드 executor 로 직렬화(동시 중복 실행 방지) + running 플래그로 트리거 중복 가드.
 * ⚠ Cloud Run 은 CPU 상시 할당(--no-cpu-throttling)이어야 응답 반환 후에도 백그라운드가 완주한다.
 */
@Component
class AsyncIngestRunner(
    private val service: SocialPostService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "social-ingest").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** 백그라운드 수집 시작. 이미 진행 중이면 false(중복 트리거 무시), 새로 시작하면 true. */
    fun trigger(autoPublish: Boolean): Boolean {
        if (!running.compareAndSet(false, true)) {
            log.info("[social] 수집 이미 진행 중 - 트리거 무시")
            return false
        }
        executor.submit {
            try {
                service.ingest(autoPublish)
            } catch (e: Exception) {
                log.error("[social] 비동기 수집 실패", e)
            } finally {
                running.set(false)
            }
        }
        return true
    }
}
