package com.aixnative.document.service

import com.aixnative.document.domain.DocumentFormat
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * 한글(.hwp, HWP 5.0 이진) 텍스트 추출(hwplib).
 *
 * 국내 공매·매각·입찰 공고문 상당수가 이 포맷이라 공고 분석의 실사용성이 여기 달려 있다.
 *
 * ⚠ [HWPReader] 가 **파일 경로만** 받아 임시파일을 거쳐야 하는데, Cloud Run 의 `/tmp` 은 tmpfs(RAM)라
 * 파일 크기만큼 컨테이너 메모리를 추가로 먹는다. 그래서 .hwp 만 업로드 상한을 가장 낮게 잡고
 * ([DocumentProperties.maxHwpBytes]) 추출 직후 반드시 삭제한다.
 *
 * HWPX(신 한글 포맷, zip 기반)는 컨테이너가 달라 지원하지 않는다.
 */
@Component
class HwpDocumentExtractor : DocumentTextExtractor {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "HWPLIB"

    override fun supports(format: DocumentFormat): Boolean = format == DocumentFormat.HWP

    override fun extract(bytes: ByteArray, fileName: String, format: DocumentFormat): RawExtraction {
        var tmp: Path? = null
        try {
            tmp = Files.createTempFile("aixnative-hwp-", ".hwp")
            Files.write(tmp, bytes)
            val hwp = HWPReader.fromFile(tmp.toAbsolutePath().toString())
                ?: throw IllegalArgumentException("한글 파일을 읽지 못했습니다. HWPX(신 포맷)는 지원하지 않습니다.")
            // 문단 사이에 표·상자 안 텍스트까지 끼워 넣는 방식 - 공고문은 핵심 값이 표 안에 있다.
            val text = TextExtractor.extract(hwp, TextExtractMethod.InsertControlTextBetweenParagraphText)
            return RawExtraction(text.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("한글 파일을 읽지 못했습니다. HWPX(신 포맷)는 지원하지 않습니다.", e)
        } finally {
            tmp?.let {
                runCatching { Files.deleteIfExists(it) }
                    .onFailure { e -> log.warn("[Document] 임시 hwp 삭제 실패 {}: {}", it, e.message) }
            }
        }
    }
}
