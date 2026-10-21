package com.aixnative.followup.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import com.aixnative.followup.domain.FollowUpTurn
import com.aixnative.followup.service.FollowUpService
import com.aixnative.followup.service.FollowUpThread
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 분석 결과 후속 논의. 모든 경로는 인증 + 현재 테넌트 스코프(다른 사용자의 runId 는 404).
 * 스레드 조회는 무료, 질문·제안·반박 검토는 AI 1회 = 크레딧(성공 시에만 차감).
 */
@RestController
@RequestMapping("/api/runs/{runId}/followup")
class FollowUpController(private val service: FollowUpService) {

    @GetMapping
    fun thread(@PathVariable runId: Long): ApiResponse<FollowUpResponse> =
        ApiResponse.ok(service.thread(runId).toResponse())

    @RequiresCredit
    @PostMapping("/ask")
    fun ask(@PathVariable runId: Long, @Valid @RequestBody req: FollowUpAskRequest): ApiResponse<FollowUpResponse> =
        ApiResponse.ok(service.ask(runId, req.question).toResponse())

    @RequiresCredit
    @PostMapping("/suggest")
    fun suggest(@PathVariable runId: Long): ApiResponse<FollowUpResponse> =
        ApiResponse.ok(service.suggest(runId).toResponse())

    @RequiresCredit
    @PostMapping("/critique")
    fun critique(@PathVariable runId: Long): ApiResponse<FollowUpResponse> =
        ApiResponse.ok(service.critique(runId).toResponse())
}

data class FollowUpAskRequest(
    @field:NotBlank(message = "질문을 입력해 주세요.")
    @field:Size(max = FollowUpService.MAX_QUESTION_CHARS, message = "질문은 2000자 이내로 입력해 주세요.")
    val question: String = "",
)

data class FollowUpTurnDto(
    val id: Long,
    val seq: Int,
    /** USER / AI / SUGGEST / CRITIQUE. CRITIQUE 의 content 는 판정 JSON 문자열. */
    val role: String,
    val content: String,
    val provider: String?,
    val createdAt: Instant?,
)

data class FollowUpResponse(
    val runId: Long,
    val turns: List<FollowUpTurnDto>,
    val creditBalance: Int,
)

private fun FollowUpTurn.toDto() = FollowUpTurnDto(
    id = requireNotNull(id),
    seq = turnSeq,
    role = role.name,
    content = content,
    provider = provider,
    createdAt = createdAt,
)

private fun FollowUpThread.toResponse() = FollowUpResponse(runId, turns.map { it.toDto() }, creditBalance)
