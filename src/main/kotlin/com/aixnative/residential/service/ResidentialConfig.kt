package com.aixnative.residential.service

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 무료 동네 리포트용 I/O 스레드풀 - 외부 공공/네이버 API 호출을 병렬화(순차 16~20콜 → 섹션별 동시).
 * Cloud Run 1 vCPU 에서도 I/O 바운드(네트워크 대기)라 스레드 다수가 유효. 데몬 스레드(앱 종료 안 막음).
 */
@Configuration
class ResidentialConfig {
    @Bean(destroyMethod = "shutdown")
    fun residentialIoExecutor(): ExecutorService =
        Executors.newFixedThreadPool(32) { r -> Thread(r, "residential-io").apply { isDaemon = true } }
}

/** 작업 목록을 [executor] 로 동시 실행 후 순서대로 결과 수집. 개별 실패는 예외 전파(호출부에서 graceful 처리). */
fun <T> ExecutorService.parMap(count: Int, task: (Int) -> T): List<T> =
    (0 until count).map { i -> CompletableFuture.supplyAsync({ task(i) }, this) }.map { it.join() }

/** 리스트 각 원소를 동시 매핑. */
fun <A, T> ExecutorService.parMap(items: List<A>, task: (A) -> T): List<T> =
    items.map { a -> CompletableFuture.supplyAsync({ task(a) }, this) }.map { it.join() }
