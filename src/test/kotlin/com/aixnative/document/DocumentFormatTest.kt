package com.aixnative.document

import com.aixnative.document.domain.DocumentFormat
import com.aixnative.document.domain.DocumentSizeClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 매직바이트 검증은 **보안 경계**다 - 확장자만 믿으면 실행파일이 파서로 들어간다.
 * 여기서 깨지면 조용히 방어가 사라지므로 양성·음성 모두 단언한다.
 */
class DocumentFormatTest {

    @Test
    fun `확장자로 포맷을 찾는다`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.ofFileName("계약서.PDF"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.ofFileName("보고서.docx"))
        assertEquals(DocumentFormat.HWP, DocumentFormat.ofFileName("공고문.hwp"))
        assertEquals(DocumentFormat.CSV, DocumentFormat.ofFileName("data.tsv"))
    }

    @Test
    fun `미지원 확장자와 확장자 없는 이름은 null`() {
        assertNull(DocumentFormat.ofFileName("악성.exe"))
        assertNull(DocumentFormat.ofFileName("확장자없음"))
        assertNull(DocumentFormat.ofFileName(null))
    }

    @Test
    fun `PDF 매직바이트를 확인한다`() {
        assertTrue(DocumentFormat.PDF.matchesMagic("%PDF-1.7 ...".toByteArray()))
        // .pdf 로 개명한 실행파일(MZ 헤더) — 거절되어야 한다.
        assertFalse(DocumentFormat.PDF.matchesMagic(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)))
    }

    @Test
    fun `OOXML 은 zip 시그니처를 요구한다`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14)
        assertTrue(DocumentFormat.DOCX.matchesMagic(zip))
        assertTrue(DocumentFormat.XLSX.matchesMagic(zip))
        assertFalse(DocumentFormat.PPTX.matchesMagic("%PDF-".toByteArray()))
    }

    @Test
    fun `HWP 는 OLE2 시그니처를 요구한다`() {
        val ole2 = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        assertTrue(DocumentFormat.HWP.matchesMagic(ole2))
        assertFalse(DocumentFormat.HWP.matchesMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `평문 계열은 시그니처를 검사하지 않는다`() {
        assertTrue(DocumentFormat.TXT.matchesMagic(byteArrayOf(0x00)))
        assertTrue(DocumentFormat.HTML.matchesMagic(ByteArray(0)))
    }

    @Test
    fun `시그니처보다 짧은 입력은 통과시키지 않는다`() {
        assertFalse(DocumentFormat.PDF.matchesMagic(byteArrayOf(0x25)))
    }

    @Test
    fun `크기 등급이 포맷별로 갈려 있다`() {
        assertEquals(DocumentSizeClass.PDF, DocumentFormat.PDF.sizeClass)
        assertEquals(DocumentSizeClass.OFFICE, DocumentFormat.XLSX.sizeClass)
        assertEquals(DocumentSizeClass.HWP, DocumentFormat.HWP.sizeClass)
        assertEquals(DocumentSizeClass.TEXT, DocumentFormat.MD.sizeClass)
    }

    @Test
    fun `accept 속성에 모든 확장자가 들어간다`() {
        val accept = DocumentFormat.ACCEPT_ATTR
        assertTrue(accept.contains(".pdf"))
        assertTrue(accept.contains(".hwp"))
        assertTrue(accept.contains(".pptx"))
    }
}
