package com.aixnative.document.service

import com.aixnative.document.domain.DocumentSizeClass
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 문서 업로드·추출 한계값. 전부 설정으로 뺀 이유는 Cloud Run 메모리를 바꾸면 같이 움직여야 하는 값들이기 때문이다.
 *
 * **메모리 근거**: 컨테이너 2Gi x `MaxRAMPercentage=75` ≈ 힙 1.5GB. PDFBox 는 원본의 3~8배 힙을 쓰므로
 * 15MB PDF 한 건이 최악 약 120MB. [maxConcurrent] 3 이면 360MB 로 힙의 1/4 이내에 머문다.
 * 동시 처리를 막지 않으면 Tomcat 기본 200 스레드가 동시에 파싱을 시작해 즉사한다.
 */
@ConfigurationProperties(prefix = "document")
data class DocumentProperties(
    /** PDF 업로드 상한(바이트). 파싱 비용이 가장 커 별도 관리. */
    val maxPdfBytes: Long = 15 * 1024 * 1024,
    /** docx/xlsx/pptx 상한. */
    val maxOfficeBytes: Long = 10 * 1024 * 1024,
    /** .hwp 상한 - 임시파일 경유(Cloud Run `/tmp` 은 tmpfs=RAM)라 가장 엄격하게 잡는다. */
    val maxHwpBytes: Long = 5 * 1024 * 1024,
    /** txt/md/csv/html 상한. */
    val maxTextBytes: Long = 2 * 1024 * 1024,
    /** 추출 텍스트 보관 상한(자). 초과분은 잘리고 `truncated=true` 로 알린다. */
    val maxTextLength: Int = 200_000,
    /** PDF 파싱 페이지 상한. 초과분은 무시(`truncated=true`). */
    val maxPdfPages: Int = 300,
    /** 추출 성공으로 볼 최소 글자수. 미만이면 스캔(이미지) 문서로 보고 명시적으로 실패시킨다. */
    val minCharCount: Int = 100,
    /** 동시 추출 상한. **OOM 1순위 방어선** - 초과 요청은 429 로 즉시 거절한다. */
    val maxConcurrent: Int = 3,
    /** 동시 추출 슬롯 대기 상한(ms). 넘으면 429. */
    val acquireTimeoutMs: Long = 3_000,
) {
    /** 크기 등급별 업로드 상한(바이트). */
    fun maxBytesOf(sizeClass: DocumentSizeClass): Long = when (sizeClass) {
        DocumentSizeClass.PDF -> maxPdfBytes
        DocumentSizeClass.OFFICE -> maxOfficeBytes
        DocumentSizeClass.HWP -> maxHwpBytes
        DocumentSizeClass.TEXT -> maxTextBytes
    }
}
