package com.aixnative.headline.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.headline.service.HeadlineService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 헤드라인 1건(제목+원문링크+발행시각). 요약·본문 없음 — 클릭 시 원문으로 이동. */
data class HeadlineView(
    val title: String,
    val url: String?,
    val publishedAt: Instant?,
)

/** 매체별 헤드라인 묶음. */
data class HeadlineGroup(
    val source: String,
    val items: List<HeadlineView>,
)

/**
 * 업계 헤드라인 보드 — 읽기. 인증 사용자 누구나(글로벌 콘텐츠, 테넌트 비스코프).
 * 제목+출처+시각만 노출하고 원문 링크로 넘긴다(제목·딥링크만 = 저작권상 안전).
 */
@RestController
@RequestMapping("/api/headlines")
class HeadlineController(private val service: HeadlineService) {

    /** 매체별로 묶은 최신 헤드라인. */
    @GetMapping
    fun list(): ApiResponse<List<HeadlineGroup>> = ApiResponse.ok(service.grouped())
}
