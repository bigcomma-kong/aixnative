package com.aixnative.document.service

import com.aixnative.document.domain.DocumentFormat
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component

/**
 * PDF 텍스트 추출(PDFBox 3.x).
 *
 * `sortByPosition = true` 로 좌표 순서를 복원한다 - 2단 편집·표가 많은 IM 은 이걸 끄면 문장이 섞여
 * AI 입력으로 못 쓴다. 대신 페이지당 정렬 비용이 있어 [DocumentProperties.maxPdfPages] 로 상한을 둔다.
 *
 * **한계**: PDFBox 는 표를 표로 인식하지 못하고 좌표 순서 텍스트로만 뱉는다. 표 중심 문서는
 * 정확도가 떨어질 수 있다(PPTX 는 표를 명시 직렬화하므로 그쪽이 낫다).
 * 스캔(이미지) PDF 는 텍스트가 0자로 나오며, OCR 은 지원하지 않는다 -
 * [DocumentExtractionService] 가 최소 글자수 검사로 명시적 실패를 돌려준다.
 */
@Component
class PdfDocumentExtractor(
    private val props: DocumentProperties,
) : DocumentTextExtractor {

    override val name: String = "PDFBOX"

    override fun supports(format: DocumentFormat): Boolean = format == DocumentFormat.PDF

    override fun extract(bytes: ByteArray, fileName: String, format: DocumentFormat): RawExtraction {
        val doc = try {
            Loader.loadPDF(bytes)
        } catch (e: InvalidPasswordException) {
            throw IllegalArgumentException("비밀번호가 걸린 PDF 입니다. 보호를 해제한 뒤 다시 올려주세요.", e)
        }
        return doc.use { pdf ->
            val total = pdf.numberOfPages
            val last = minOf(total, props.maxPdfPages)
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = last
            }
            RawExtraction(
                text = stripper.getText(pdf),
                pageCount = total,
                truncated = last < total,
            )
        }
    }
}
