package com.aixnative.document.service

import com.aixnative.document.domain.DocumentFormat
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 평문 계열(txt/md/csv/tsv/html) 텍스트 추출.
 *
 * 매직바이트가 없는 포맷이라 **UTF-8 디코딩 자체를 검증 수단으로 쓴다** - 실행파일이나 이진 데이터를
 * `.txt` 로 올리면 디코딩이 실패해 거절된다(EUC-KR 문서도 여기서 걸리므로 안내 메시지에 인코딩을 명시).
 *
 * HTML 은 정규식으로 태그를 걷어내지 않고 jsoup 으로 파싱한다 - 스크립트·스타일 본문이 그대로 딸려
 * 들어가는 것을 막고, 블록 요소 사이 줄바꿈을 보존하기 위함(이미 의존성이 있어 추가 비용 0).
 */
@Component
class PlainTextExtractor : DocumentTextExtractor {

    override val name: String = "PLAIN"

    override fun supports(format: DocumentFormat): Boolean = when (format) {
        DocumentFormat.TXT, DocumentFormat.MD, DocumentFormat.CSV, DocumentFormat.HTML -> true
        else -> false
    }

    override fun extract(bytes: ByteArray, fileName: String, format: DocumentFormat): RawExtraction {
        val raw = decodeUtf8(bytes)
        val text = if (format == DocumentFormat.HTML) Jsoup.parse(raw).wholeText() else raw
        return RawExtraction(text)
    }

    /** 엄격 UTF-8 디코딩. BOM 은 제거한다(앞에 남으면 첫 토큰이 깨져 보인다). */
    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException(
                "텍스트 파일을 읽을 수 없습니다. UTF-8 로 저장된 파일인지 확인해 주세요(EUC-KR 은 지원하지 않습니다).",
                e,
            )
        }
        return decoded.removePrefix("﻿")
    }
}
