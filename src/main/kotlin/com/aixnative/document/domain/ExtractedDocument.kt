package com.aixnative.document.domain

/**
 * 문서 1건의 텍스트 추출 결과.
 *
 * 원본 바이트는 어디에도 남기지 않는다 - 계약서·공고문은 당사자명·사업자번호·거래금액의 집합이라
 * 보관하는 순간 개인정보·영업비밀 보관 책임이 생기는데, 재열람 수요는 원본이 아니라 **분석 결과**에 있다
 * (결과는 `ai_tool_run` 에 남는다).
 *
 * @param truncated 상한([com.aixnative.document.service.DocumentProperties.maxTextLength]) 초과로 잘렸는지.
 *   true 면 화면이 "앞부분만 사용" 경고를 띄우고 사용자가 필요한 부분을 직접 편집할 수 있게 한다.
 */
data class ExtractedDocument(
    val fileName: String,
    val format: DocumentFormat,
    val byteSize: Long,
    val text: String,
    val charCount: Int,
    val pageCount: Int? = null,
    val extractor: String,
    val truncated: Boolean = false,
)
