package com.aixnative.social.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 소셜 장기 작업(수집·이미지 재생성)을 백그라운드로 실행 - 관리자/스케줄러 트리거가 즉시 반환하도록
 * (브라우저 524·Cloud Run 300s 요청 상한 회피). 커뮤니티 스크래핑 + Claude 각색 + 이미지 + 렌더가
 * 분 단위라 동기로는 HTTP 타임아웃에 걸린다.
 *
 * 단일 스레드 executor 로 **모든 작업을 직렬화**한다 - 동시 중복 실행을 막고, Cloud Run 1Gi 메모리에서
 * 이미지 생성·Node 렌더가 겹쳐 터지지 않게 한다. 중복 트리거는 플래그([running])와 진행 중 id 집합
 * ([regenerating])으로 각각 가드한다.
 *
 * ⚠ Cloud Run 은 CPU 상시 할당(--no-cpu-throttling)이어야 응답 반환 후에도 백그라운드가 완주한다.
 * → `deploy/deploy.sh` 의 `gcloud run deploy` 에 해당 플래그가 있어야 한다.
 */
@Component
class AsyncIngestRunner(
    private val service: SocialPostService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "social-worker").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /** 이미지 재생성이 진행 중인 게시물 id. 같은 글의 중복 트리거만 막고 다른 글은 큐에 쌓인다. */
    private val regenerating: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun isRunning(): Boolean = running.get()

    /** 해당 게시물의 이미지 재생성이 진행(또는 대기) 중인지. */
    fun isRegenerating(id: Long): Boolean = regenerating.contains(id)

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

    /**
     * 백그라운드 이미지 재생성 시작. 같은 글이 이미 진행 중이면 false.
     *
     * 동기로 두면 장면당 최악 60초대(재시도 포함) x 장면 수 + Node 렌더까지 더해져 Cloud Run 300s 를
     * 넘길 수 있어 비동기로 돌린다. 완료 여부는 관리자 화면이 목록 폴링으로 확인한다.
     */
    fun triggerRegenerate(id: Long): Boolean {
        if (!regenerating.add(id)) {
            log.info("[social] 이미지 재생성 이미 진행 중 - 트리거 무시 id={}", id)
            return false
        }
        executor.submit {
            try {
                service.regenerateImages(id)
            } catch (e: Exception) {
                log.error("[social] 비동기 이미지 재생성 실패 id={}", id, e)
            } finally {
                regenerating.remove(id)
            }
        }
        return true
    }
}
