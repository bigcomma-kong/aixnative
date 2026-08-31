package com.aixnative.document

import com.aixnative.document.domain.DocumentFormat
import com.aixnative.document.service.OfficeDocumentExtractor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertTrue

/**
 * 픽스처 파일을 커밋하지 않고 POI 로 메모리 상에서 문서를 만들어 추출 왕복을 검증한다
 * (바이너리 픽스처는 리뷰가 불가능하고 리포지터리만 무겁게 한다).
 *
 * 특히 **표 직렬화**를 확인한다 - IM·제안서의 핵심 수치는 대부분 표 안에 있어서,
 * 표를 건너뛰면 AI 입력에서 정작 필요한 값이 빠진다.
 */
class OfficeDocumentExtractorTest {

    private val extractor = OfficeDocumentExtractor()

    @Test
    fun `docx 본문과 표를 뽑는다`() {
        val bytes = ByteArrayOutputStream().use { out ->
            XWPFDocument().use { doc ->
                doc.createParagraph().createRun().setText("역삼동 오피스 매입 검토")
                val table = doc.createTable(1, 2)
                table.getRow(0).getCell(0).text = "매입가"
                table.getRow(0).getCell(1).text = "120억원"
                doc.write(out)
            }
            out.toByteArray()
        }

        val text = extractor.extract(bytes, "im.docx", DocumentFormat.DOCX).text

        assertTrue(text.contains("역삼동 오피스 매입 검토"), text)
        assertTrue(text.contains("매입가"), text)
        assertTrue(text.contains("120억원"), text)
    }

    @Test
    fun `xlsx 는 시트 경계와 셀 값을 직렬화한다`() {
        val bytes = ByteArrayOutputStream().use { out ->
            XSSFWorkbook().use { wb ->
                val sheet = wb.createSheet("렌트롤")
                val row = sheet.createRow(0)
                row.createCell(0).setCellValue("임차인")
                row.createCell(1).setCellValue("월세")
                val row2 = sheet.createRow(1)
                row2.createCell(0).setCellValue("가나상사")
                row2.createCell(1).setCellValue(3_500_000.0)
                wb.write(out)
            }
            out.toByteArray()
        }

        val text = extractor.extract(bytes, "rentroll.xlsx", DocumentFormat.XLSX).text

        assertTrue(text.contains("--- SHEET 렌트롤 ---"), text)
        assertTrue(text.contains("임차인 | 월세"), text)
        assertTrue(text.contains("가나상사"), text)
    }

    @Test
    fun `지원 포맷만 받는다`() {
        assertTrue(extractor.supports(DocumentFormat.DOCX))
        assertTrue(extractor.supports(DocumentFormat.XLSX))
        assertTrue(extractor.supports(DocumentFormat.PPTX))
        assertTrue(!extractor.supports(DocumentFormat.PDF))
        assertTrue(!extractor.supports(DocumentFormat.HWP))
    }
}
