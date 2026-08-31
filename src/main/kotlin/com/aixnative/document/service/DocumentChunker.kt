package com.aixnative.document.service

/**
 * 긴 문서를 문단 경계로 나눈다(map-reduce 요약용). 순수 함수(무상태).
 *
 * ⚠ **계약서 검토·공고 추출에는 쓰지 않는다.** 그 도구들은 "요약"이 아니라 "전문 대조 검토"라,
 * 조각내면 조문 간 모순(inconsistencies)·상호참조 검증이 원리적으로 불가능해진다. 대신 상한에서
 * 자르고 사용자에게 잘렸다고 알린다. 이 유틸은 향후 "긴 문서 요약" 도구가 소비할 자리다.
 *
 * 문장이 아니라 **문단** 경계로 자르는 이유는, 문장 중간에서 끊으면 조각마다 문맥이 깨져
 * 부분 요약 품질이 급격히 나빠지기 때문이다.
 */
object DocumentChunker {

    /** 이 길이 이하면 자르지 않고 1조각으로 돌려준다. */
    const val CHUNK_THRESHOLD = 45_000

    /** 조각 목표 길이. */
    const val CHUNK_SIZE = 18_000

    /** 조각 수 상한 - AI 호출 폭증 방지. 초과분은 버린다(요약 목적상 앞부분 밀도가 높다). */
    const val MAX_CHUNKS = 12

    /**
     * @return 1개 이상의 조각. 빈 입력은 빈 리스트.
     */
    fun split(
        text: String,
        threshold: Int = CHUNK_THRESHOLD,
        chunkSize: Int = CHUNK_SIZE,
        maxChunks: Int = MAX_CHUNKS,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        if (text.length <= threshold) return listOf(text)

        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (para in text.split(PARAGRAPH)) {
            // 문단 하나가 조각보다 크면 그 문단만 단독 조각으로 낸다(더 쪼개면 문맥이 깨진다).
            if (para.length >= chunkSize) {
                if (sb.isNotEmpty()) {
                    chunks += sb.toString().trim()
                    sb.setLength(0)
                }
                chunks += para.trim()
            } else {
                if (sb.length + para.length > chunkSize && sb.isNotEmpty()) {
                    chunks += sb.toString().trim()
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(para)
            }
            if (chunks.size >= maxChunks) break
        }
        if (sb.isNotEmpty() && chunks.size < maxChunks) chunks += sb.toString().trim()
        return chunks.filter { it.isNotBlank() }.take(maxChunks)
    }

    private val PARAGRAPH = Regex("\\n{2,}")
}
