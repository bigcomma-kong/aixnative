package com.aixnative.contract.web

import com.fasterxml.jackson.databind.JsonNode

/**
 * 계약서 검토·수정안 공통 응답.
 *
 * [analysis] 가 null 이면 AI 가 끝내 형식을 지키지 못한 경우로, 그때만 [analysisRaw] 에 원문이 담긴다.
 * "아무것도 못 주는" 실패 대신 서술 텍스트라도 보여 주기 위한 폴백이다(기존 심화 분석과 같은 규약).
 */
data class ContractResponse(
    val runId: Long,
    val dealId: Long,
    val tool: String,
    val perspective: String,
    val perspectiveLabel: String,
    val analysis: JsonNode?,
    val analysisRaw: String?,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)

/** 묶음 교차검토 응답 - 개별 조항이 아니라 문서 사이 관계를 담는다. */
data class ContractSetCompareResponse(
    val runId: Long,
    val dealId: Long,
    val deal: JsonNode?,
    val analysisRaw: String?,
    val count: Int,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)
