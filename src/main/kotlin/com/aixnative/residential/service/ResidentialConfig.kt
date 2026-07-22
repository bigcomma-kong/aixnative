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

/** "서울특별시"→"서울", "경기도"→"경기" 등 시도 축약(분양공고 지역 필터·표시용). 없으면 null. */
fun shortSido(addr: String?): String? {
    val first = addr?.trim()?.split(" ")?.firstOrNull()?.trim().orEmpty()
    return first
        .removeSuffix("특별자치시").removeSuffix("특별자치도")
        .removeSuffix("특별시").removeSuffix("광역시").removeSuffix("도")
        .ifBlank { null }
}

private val ADMIN_TOKEN = Regex(".*[시군구읍면동리가]$")

/**
 * 지번/도로명 주소에서 POI 검색용 지역 라벨 추출 - 행정구역 토큰(시/군/구/읍/면/동/리)만 남겨 번지·층·건물명 제거.
 * "경기도 용인시 수지구 신봉동 145-1 1층" → "용인시 수지구 신봉동". 없으면 null.
 */
fun areaLabelOf(addr: String?): String? {
    val toks = addr?.trim()?.split(" ")?.filter { it.isNotBlank() && ADMIN_TOKEN.matches(it) } ?: return null
    return toks.takeLast(3).joinToString(" ").ifBlank { null }
}
