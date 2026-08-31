package com.aixnative.document.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.document.domain.DocumentFormat
import com.aixnative.document.service.DocumentExtractionService
import com.aixnative.document.service.DocumentProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 문서 업로드 → 텍스트 추출. **무과금**(AI 미호출)이며 인증만 요구한다
 * (api 하위 경로는 SecurityConfig 의 `anyRequest().authenticated()` 로 이미 보호된다).
 *
 * 여기서 받은 `text` 를 클라이언트가 기존 분석 엔드포인트(심화 분석·계약서 검토·공고 분석)의
 * 텍스트 필드에 실어 보낸다. 원본 파일은 저장하지 않는다.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController(
    private val service: DocumentExtractionService,
    private val props: DocumentProperties,
) {
    /** 파일 1건 → 정제된 텍스트. 실패는 전부 4xx + 한국어 사유로 돌려준다. */
    @PostMapping("/extract")
    fun extract(@RequestPart("file") file: MultipartFile): ApiResponse<DocumentExtractResponse> =
        ApiResponse.ok(DocumentExtractResponse.of(service.extract(file)))

    /** 업로드 UI 안내값(확장자·용량). 화면이 서버 상한과 어긋나지 않게 서버를 단일 소스로 쓴다. */
    @GetMapping("/limits")
    fun limits(): ApiResponse<DocumentLimitsResponse> = ApiResponse.ok(
        DocumentLimitsResponse(
            accept = DocumentFormat.ACCEPT_ATTR,
            supportedLabel = DocumentFormat.SUPPORTED_LABEL,
            maxPdfMb = props.maxPdfBytes / MB,
            maxOfficeMb = props.maxOfficeBytes / MB,
            maxHwpMb = props.maxHwpBytes / MB,
            maxTextMb = props.maxTextBytes / MB,
        ),
    )

    private companion object {
        const val MB = 1024L * 1024L
    }
}
