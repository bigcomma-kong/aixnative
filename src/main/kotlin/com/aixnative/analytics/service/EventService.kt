package com.aixnative.analytics.service

import com.aixnative.analytics.domain.UserEvent
import com.aixnative.analytics.repository.UserEventRepository
import com.aixnative.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 경량 행동 이벤트 수집. 공개 엔드포인트에서 호출되므로 **화이트리스트 이벤트만** 저장하고
 * 문자열 길이를 제한한다(임의 데이터 적재·스토리지 남용 방지). 로그인 상태면 tenant/user 를 붙인다.
 * 수집 실패가 사용자 흐름을 막지 않도록 절대 예외를 전파하지 않는다.
 */
@Service
class EventService(private val events: UserEventRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 저장을 허용하는 이벤트(퍼널 신호). 미등록 이벤트는 조용히 무시(주입 방지). */
    private val allowed = setOf(
        "page_view",       // 메뉴/화면 진입
        "free_calc",       // 무료 ProForma 계산 실행
        "analysis_start",  // 과금 분석 버튼 클릭(크레딧 게이트 진입)
        "analysis_done",   // 과금 분석 성공(프론트 확인)
        "signup_view",     // 가입 화면 노출
        "checkout_view",   // 결제/크레딧요청 화면 노출
        "credit_request",  // 크레딧 요청 메일 CTA 클릭
    )

    /**
     * 로그인 전엔 진입 자체가 불가한 인앱 화면 경로(SPA 탭). 익명(토큰 없음/만료)으로 이 경로 이벤트가
     * 오면 만료 토큰을 문 "좀비 세션" 흔적이므로 저장하지 않는다(방문자 지표 오염 방지).
     * path 없는 퍼널 이벤트(checkout_view·credit_request 등)는 영향 없음.
     */
    private val protectedPaths = setOf("feed", "underwrite", "advanced", "pm", "mydeals", "admin")

    @Transactional
    fun record(event: String, path: String?, meta: String?) {
        val name = event.trim()
        if (name !in allowed) return // 화이트리스트 외 → 무시

        val ctx = TenantContext.currentOrNull()
        if (ctx?.userId == null && path?.trim() in protectedPaths) return // 익명 + 보호경로 = 좀비 → 스킵

        runCatching {
            events.save(
                UserEvent(
                    tenantId = ctx?.tenantId,
                    userId = ctx?.userId,
                    event = name,
                    path = path?.trim()?.take(MAX_PATH),
                    meta = meta?.trim()?.take(MAX_META),
                ),
            )
        }.onFailure { log.debug("[event] 적재 실패({}): {}", name, it.message) }
    }

    companion object {
        private const val MAX_PATH = 200
        private const val MAX_META = 500
    }
}
