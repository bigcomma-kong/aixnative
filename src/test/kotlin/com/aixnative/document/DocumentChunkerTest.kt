package com.aixnative.document

import com.aixnative.document.service.DocumentChunker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentChunkerTest {

    @Test
    fun `임계 이하면 자르지 않는다`() {
        val text = "짧은 문서입니다.\n\n두 번째 문단."
        assertEquals(listOf(text), DocumentChunker.split(text))
    }

    @Test
    fun `빈 입력은 빈 리스트`() {
        assertTrue(DocumentChunker.split("").isEmpty())
        assertTrue(DocumentChunker.split("   ").isEmpty())
    }

    @Test
    fun `임계를 넘으면 문단 경계로 나눈다`() {
        // 문단 하나가 chunkSize 보다 작아 여러 개가 한 조각에 묶이는 조건.
        val para = "가".repeat(300)
        val text = (1..40).joinToString("\n\n") { para }

        val chunks = DocumentChunker.split(text, threshold = 1_000, chunkSize = 1_000, maxChunks = 12)

        assertTrue(chunks.size > 1, "조각이 나뉘어야 한다: ${chunks.size}")
        // 문단 중간에서 끊기지 않았는지 - 모든 조각이 온전한 문단들로만 이루어져야 한다.
        chunks.forEach { chunk ->
            chunk.split("\n\n").forEach { p -> assertEquals(300, p.length, "문단이 잘렸다") }
        }
    }

    @Test
    fun `조각 수 상한을 넘지 않는다`() {
        val para = "나".repeat(500)
        val text = (1..200).joinToString("\n\n") { para }

        val chunks = DocumentChunker.split(text, threshold = 1_000, chunkSize = 1_000, maxChunks = 5)

        assertEquals(5, chunks.size)
    }

    @Test
    fun `조각보다 큰 단일 문단은 단독 조각으로 낸다`() {
        val huge = "다".repeat(3_000)
        val text = "$huge\n\n짧은 문단."

        val chunks = DocumentChunker.split(text, threshold = 1_000, chunkSize = 1_000, maxChunks = 12)

        assertEquals(huge, chunks.first())
        assertTrue(chunks.size >= 2)
    }
}
