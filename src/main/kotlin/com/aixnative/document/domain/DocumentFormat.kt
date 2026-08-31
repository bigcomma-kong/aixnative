package com.aixnative.document.domain

/** ZIP 컨테이너 시그니처 - OOXML(docx/xlsx/pptx)은 전부 zip 이다. */
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/** OLE2 복합문서 시그니처 - HWP 5.0 이진(구 MS Office 도 같은 컨테이너). */
private val OLE2_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)

/** "%PDF" */
private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46)

/**
 * 업로드 문서의 크기 등급 - 실제 바이트 상한은 [com.aixnative.document.service.DocumentProperties] 가 갖는다.
 * 포맷마다 파싱 비용이 크게 달라(PDF > Office > 한글 > 평문) 한 값으로 묶을 수 없다.
 */
enum class DocumentSizeClass { PDF, OFFICE, HWP, TEXT }

/**
 * 지원 문서 포맷 - 확장자·매직바이트·크기등급의 단일 소스.
 *
 * **매직바이트를 함께 갖는 이유**: 확장자와 Content-Type 은 클라이언트가 마음대로 붙일 수 있다.
 * 실행파일을 `.pdf` 로 개명한 업로드를 파서에 넘기지 않으려면 앞 몇 바이트를 직접 봐야 한다.
 * 평문 계열([TXT]/[MD]/[CSV]/[HTML])은 고정 시그니처가 없어 검사를 건너뛰고, 대신 크기 상한을
 * 가장 낮게 잡는다.
 */
enum class DocumentFormat(
    val extensions: List<String>,
    val sizeClass: DocumentSizeClass,
    val magic: List<ByteArray> = emptyList(),
) {
    PDF(listOf("pdf"), DocumentSizeClass.PDF, listOf(PDF_MAGIC)),
    DOCX(listOf("docx"), DocumentSizeClass.OFFICE, listOf(ZIP_MAGIC)),
    XLSX(listOf("xlsx"), DocumentSizeClass.OFFICE, listOf(ZIP_MAGIC)),
    PPTX(listOf("pptx"), DocumentSizeClass.OFFICE, listOf(ZIP_MAGIC)),
    HWP(listOf("hwp"), DocumentSizeClass.HWP, listOf(OLE2_MAGIC)),
    TXT(listOf("txt"), DocumentSizeClass.TEXT),
    MD(listOf("md", "markdown"), DocumentSizeClass.TEXT),
    CSV(listOf("csv", "tsv"), DocumentSizeClass.TEXT),
    HTML(listOf("html", "htm"), DocumentSizeClass.TEXT),
    ;

    /** 앞 바이트가 이 포맷의 시그니처와 맞는지. 시그니처가 없는 평문 계열은 항상 true. */
    fun matchesMagic(bytes: ByteArray): Boolean {
        if (magic.isEmpty()) return true
        return magic.any { sig -> bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it] } }
    }

    companion object {
        /** 사용자에게 보여줄 지원 목록 문구(에러 메시지·업로드 안내에서 공용). */
        const val SUPPORTED_LABEL: String = "PDF · DOCX · XLSX · PPTX · HWP · TXT · MD · CSV · HTML"

        /** 파일 선택 다이얼로그용 accept 속성 값. */
        val ACCEPT_ATTR: String = entries.flatMap { f -> f.extensions.map { ".$it" } }.joinToString(",")

        /** 파일명의 확장자로 포맷을 판정한다. 확장자가 없거나 미지원이면 null. */
        fun ofFileName(fileName: String?): DocumentFormat? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
