package com.aixnative.document.web

import com.aixnative.document.domain.ExtractedDocument

/**
 * 문서 추출 응답. 원본 파일은 서버에 남지 않으므로 클라이언트가 [text] 를 보관했다가
 * 분석 요청에 실어 보낸다(추출 ≠ 분석 분리 설계).
 */
data class DocumentExtractResponse(
    val fileName: String,
    val format: String,
    val byteSize: Long,
    val charCount: Int,
    val pageCount: Int?,
    val extractor: String,
    /** true 면 상한 때문에 앞부분만 담겼다 - 화면이 경고를 띄운다. */
    val truncated: Boolean,
    val text: String,
) {
    companion object {
        fun of(d: ExtractedDocument) = DocumentExtractResponse(
            fileName = d.fileName,
            format = d.format.name,
            byteSize = d.byteSize,
            charCount = d.charCount,
            pageCount = d.pageCount,
            extractor = d.extractor,
            truncated = d.truncated,
            text = d.text,
        )
    }
}

/** 업로드 UI 가 쓰는 제약 안내(확장자·용량). 서버 값을 단일 소스로 노출해 화면과 어긋나지 않게 한다. */
data class DocumentLimitsResponse(
    val accept: String,
    val supportedLabel: String,
    val maxPdfMb: Long,
    val maxOfficeMb: Long,
    val maxHwpMb: Long,
    val maxTextMb: Long,
)
