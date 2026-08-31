package com.aixnative.document.service

import com.aixnative.document.domain.DocumentFormat

/** 추출기 1회 실행 결과(정제 전 원문). 페이지 개념이 없는 포맷은 [pageCount] 가 null. */
data class RawExtraction(
    val text: String,
    val pageCount: Int? = null,
    /** 포맷 자체 상한(예: PDF 페이지 수)으로 일부를 건너뛰었는지. */
    val truncated: Boolean = false,
)

/**
 * 문서 바이트 → 텍스트 추출 전략.
 *
 * 구현체를 `@Component` 로 등록하면 [DocumentExtractionService] 가 `List<DocumentTextExtractor>` 로
 * 주입받아 [supports] 로 라우팅한다(기존 `AiProvider`·`ImageEngine` 과 같은 패턴).
 * 새 포맷 지원은 구현체 하나를 추가하는 것으로 끝난다.
 */
interface DocumentTextExtractor {
    /** 로그·응답에 노출되는 추출기 이름("PDFBOX"·"POI"·"PLAIN"·"HWPLIB"). */
    val name: String

    fun supports(format: DocumentFormat): Boolean

    /**
     * @throws IllegalArgumentException 사용자에게 그대로 보여줄 수 있는 실패(암호화 문서 등).
     *   그 외 예외는 [DocumentExtractionService] 가 일반 메시지로 감싼다.
     */
    fun extract(bytes: ByteArray, fileName: String, format: DocumentFormat): RawExtraction
}
