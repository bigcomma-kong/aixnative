package com.aixnative.document.service

import com.aixnative.document.domain.DocumentFormat
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * OOXML(docx/xlsx/pptx) 텍스트 추출(Apache POI).
 *
 * **표를 반드시 직렬화한다.** IM·제안서의 핵심 수치(매입가·NOI·면적)는 대부분 표 안에 있어,
 * 표를 건너뛰면 AI 입력에서 정작 필요한 값이 빠진다. 셀은 `|` 로, 행은 줄바꿈으로 잇고
 * 슬라이드/시트 경계를 표시해 AI 가 구조를 읽을 수 있게 한다.
 *
 * 구포맷(.doc/.xls/.ppt)은 poi-scratchpad 가 필요한데 이미지 크기 대비 수요가 낮아 지원하지 않는다.
 */
@Component
class OfficeDocumentExtractor : DocumentTextExtractor {

    override val name: String = "POI"

    override fun supports(format: DocumentFormat): Boolean =
        format == DocumentFormat.DOCX || format == DocumentFormat.XLSX || format == DocumentFormat.PPTX

    override fun extract(bytes: ByteArray, fileName: String, format: DocumentFormat): RawExtraction {
        // zip bomb 방어 - 압축 해제 비율·엔트리 수·총량 상한. POI 전역 설정이라 추출 직전에 건다.
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO)
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES)
        ZipSecureFile.setMaxTextSize(MAX_TEXT_BYTES)

        return when (format) {
            DocumentFormat.DOCX -> RawExtraction(extractDocx(bytes))
            DocumentFormat.XLSX -> RawExtraction(extractXlsx(bytes))
            DocumentFormat.PPTX -> extractPptx(bytes)
            else -> error("지원하지 않는 포맷: $format")
        }
    }

    private fun extractDocx(bytes: ByteArray): String =
        ByteArrayInputStream(bytes).use { input ->
            XWPFDocument(input).use { doc -> XWPFWordExtractor(doc).use { it.text } }
        }

    /** 시트별로 구분자를 넣고 셀을 화면 표시 문자열(DataFormatter)로 직렬화한다. 빈 행은 건너뛴다. */
    private fun extractXlsx(bytes: ByteArray): String {
        val fmt = DataFormatter()
        val sb = StringBuilder()
        ByteArrayInputStream(bytes).use { input ->
            WorkbookFactory.create(input).use { wb ->
                for (sheet in wb) {
                    sb.append("\n--- SHEET ").append(sheet.sheetName).append(" ---\n")
                    for (row in sheet) {
                        val line = row.joinToString(" | ") { fmt.formatCellValue(it).trim() }
                        if (line.isNotBlank() && line.any { it != '|' && !it.isWhitespace() }) {
                            sb.append(line).append('\n')
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    /** 슬라이드 경계를 표시하고, 그룹 도형은 재귀로 펼치며, 표는 행 단위로 직렬화한다. */
    private fun extractPptx(bytes: ByteArray): RawExtraction {
        val sb = StringBuilder()
        var slides = 0
        ByteArrayInputStream(bytes).use { input ->
            XMLSlideShow(input).use { ppt ->
                ppt.slides.forEachIndexed { i, slide ->
                    slides = i + 1
                    sb.append("\n--- SLIDE ").append(i + 1).append(" ---\n")
                    slide.shapes.forEach { appendShape(it, sb) }
                }
            }
        }
        return RawExtraction(sb.toString(), pageCount = slides)
    }

    private fun appendShape(shape: XSLFShape, sb: StringBuilder) {
        when (shape) {
            is XSLFTable -> shape.rows.forEach { row ->
                val line = row.cells.joinToString(" | ") { it.text.orEmpty().replace('\n', ' ').trim() }
                if (line.isNotBlank()) sb.append(line).append('\n')
            }
            is XSLFGroupShape -> shape.shapes.forEach { appendShape(it, sb) }
            is XSLFTextShape -> shape.text?.takeIf { it.isNotBlank() }?.let { sb.append(it.trim()).append('\n') }
            else -> Unit // 이미지·커넥터 등 텍스트 없는 도형
        }
    }

    private companion object {
        /** 압축 해제 비율 하한 - 이보다 잘 풀리면 zip bomb 으로 보고 POI 가 예외를 던진다. */
        const val MIN_INFLATE_RATIO = 0.01
        const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
        const val MAX_TEXT_BYTES = 50L * 1024 * 1024
    }
}
