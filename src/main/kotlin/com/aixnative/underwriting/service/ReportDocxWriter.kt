package com.aixnative.underwriting.service

import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.TableWidthType
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * 투자 보고서 HTML([ReportService] 산출물) → Word(.docx).
 *
 * 범용 HTML 변환기가 아니라 **우리가 만든 보고서 HTML**(h1~h4·p·ul/ol·table·blockquote·strong)만 다룬다.
 * 그래서 의존성을 늘리지 않고 jsoup + POI 로 충분하다. 모르는 태그는 자식만 따라 내려가 텍스트를 잃지 않는다.
 * HTML 을 .doc 확장자로 내려 주는 방식과 달리 Word·한컴·구글 문서·Pages 에서 모두 정상으로 열린다.
 */
object ReportDocxWriter {

    private const val FONT = "Malgun Gothic"
    private const val BODY_PT = 10
    private val HEADING_PT = mapOf("h1" to 18, "h2" to 14, "h3" to 12, "h4" to 11)

    fun write(html: String): ByteArray {
        val doc = XWPFDocument()
        val body = Jsoup.parse(html).body()
        body.select("script, style, nav, .no-print, button").remove()
        body.childNodes().forEach { block(doc, it) }
        return ByteArrayOutputStream().use { out -> doc.write(out); doc.close(); out.toByteArray() }
    }

    private fun block(doc: XWPFDocument, node: Node) {
        when (node) {
            is TextNode -> node.text().trim().takeIf { it.isNotEmpty() }?.let { paragraph(doc).run(it) }
            is Element -> element(doc, node)
        }
    }

    private fun element(doc: XWPFDocument, e: Element) {
        when (val tag = e.tagName().lowercase()) {
            "h1", "h2", "h3", "h4" -> {
                val p = paragraph(doc)
                p.spacingBefore = if (tag == "h1") 0 else 240
                p.spacingAfter = 80
                inline(p, e, bold = true, size = HEADING_PT.getValue(tag))
            }
            "p" -> inline(paragraph(doc), e)
            "blockquote" -> paragraph(doc).also { it.indentationLeft = 400 }.let { inline(it, e, italic = true) }
            "ul", "ol" -> e.children().toList().filter { it.tagName() == "li" }.forEachIndexed { i, li ->
                val p = paragraph(doc)
                p.indentationLeft = 360
                p.indentationHanging = 240
                p.createRun().apply { setText(if (tag == "ol") "${i + 1}. " else "• "); fontFamily = FONT; fontSize = BODY_PT }
                inline(p, li)
            }
            "table" -> table(doc, e)
            "br" -> paragraph(doc)
            "hr" -> paragraph(doc).also { it.borderBottom = org.apache.poi.xwpf.usermodel.Borders.SINGLE }
            // 구획 태그는 자식만 따라 내려간다. 단, 인라인 내용만 가진 div 는 문단 하나로.
            else -> if (e.children().none { it.isBlock }) {
                if (e.text().isNotBlank()) inline(paragraph(doc), e)
            } else {
                e.childNodes().forEach { block(doc, it) }
            }
        }
    }

    private fun table(doc: XWPFDocument, e: Element) {
        val rows = e.select("tr").toList().filter { it.closest("table") == e }
        if (rows.isEmpty()) return
        val cols = rows.maxOf { r -> r.children().toList().filter { it.tagName() in CELL_TAGS }.size }.coerceAtLeast(1)
        val t: XWPFTable = doc.createTable(rows.size, cols)
        t.setWidth("100%")
        t.setWidthType(TableWidthType.PCT)
        rows.forEachIndexed { ri, tr ->
            val cells = tr.children().toList().filter { it.tagName() in CELL_TAGS }
            for (ci in 0 until cols) {
                val cell = t.getRow(ri).getCell(ci)
                val src = cells.getOrNull(ci)
                val p = cell.paragraphs.first()
                if (src != null) inline(p, src, bold = src.tagName() == "th", size = BODY_PT - 1)
                if (src?.tagName() == "th") cell.color = "EEF1F6"
            }
        }
        // 표 다음 간격.
        paragraph(doc).spacingAfter = 120
    }

    /** 인라인 내용(텍스트·strong·em·span·a·br)을 런으로. 서식은 부모에서 상속한다. */
    private fun inline(p: XWPFParagraph, e: Element, bold: Boolean = false, italic: Boolean = false, size: Int = BODY_PT) {
        e.childNodes().forEach { n ->
            when (n) {
                is TextNode -> n.text().takeIf { it.isNotEmpty() }?.let { text ->
                    p.createRun().apply {
                        setText(text.replace(Regex("\\s+"), " "))
                        isBold = bold
                        isItalic = italic
                        fontFamily = FONT
                        fontSize = size
                    }
                }
                is Element -> when (n.tagName().lowercase()) {
                    "br" -> p.createRun().addBreak()
                    "strong", "b" -> inline(p, n, bold = true, italic = italic, size = size)
                    "em", "i" -> inline(p, n, bold = bold, italic = true, size = size)
                    "ul", "ol", "table" -> Unit // 셀 안의 블록은 단순화(보고서 HTML 에는 거의 없다)
                    else -> inline(p, n, bold, italic, size)
                }
            }
        }
    }

    private fun paragraph(doc: XWPFDocument): XWPFParagraph = doc.createParagraph().apply {
        alignment = ParagraphAlignment.LEFT
        spacingAfter = 100
    }

    private fun XWPFParagraph.run(text: String) {
        createRun().apply { setText(text); fontFamily = FONT; fontSize = BODY_PT }
    }

    private val CELL_TAGS = setOf("td", "th")

    @Suppress("unused")
    private val TWIPS_PER_INCH = BigInteger.valueOf(1440)
}
