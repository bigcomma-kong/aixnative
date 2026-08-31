package com.aixnative.document.service

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.TooManyRequestsException
import com.aixnative.document.domain.DocumentFormat
import com.aixnative.document.domain.ExtractedDocument
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 업로드 문서 → 텍스트. 검증·동시성 제어·추출기 라우팅·정제를 한 곳에 모은다.
 *
 * **이 서비스는 AI 를 부르지 않고 크레딧도 쓰지 않는다.** 추출과 분석을 별개 요청으로 나눈 것이 설계의 핵심이다.
 *  - Cloud Run 300초 요청 예산을 추출과 AI 가 나눠 갖지 않는다.
 *  - 스캔본·암호화·깨진 인코딩을 **과금 전에** 걸러, 사용자가 몇 분 기다린 뒤 실패를 보지 않게 한다.
 *  - 사용자가 추출 결과를 눈으로 보고 고친 뒤 분석할 수 있다(입력 품질 = 결과 품질).
 *  - 기존 분석 엔드포인트들이 이미 텍스트 필드를 받으므로, 프런트가 결과를 채워 넣기만 하면
 *    **백엔드 분석 코드 변경 없이** 파일 입력을 얻는다.
 *
 * 검증 순서는 싼 것부터: 확장자 → 크기 → 매직바이트 → (슬롯 확보) → 파싱.
 */
@Service
class DocumentExtractionService(
    private val extractors: List<DocumentTextExtractor>,
    private val props: DocumentProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 동시 추출 슬롯. PDFBox/POI 는 원본의 몇 배 힙을 쓰므로, 이게 없으면 Tomcat 워커 스레드가
     * 동시에 파싱을 시작해 컨테이너가 OOM 으로 죽는다. **OOM 1순위 방어선.**
     */
    private val slots = Semaphore(props.maxConcurrent, true)

    /** 사용자별 업로드 횟수(고정 윈도). 무과금 엔드포인트라 남용 방어가 필요하다. */
    private val buckets = ConcurrentHashMap<Long, Window>()

    private class Window(@Volatile var startedAt: Long, @Volatile var count: Int)

    /**
     * @throws BadRequestException 사용자가 고칠 수 있는 입력 문제(포맷·용량·스캔본·암호화).
     * @throws TooManyRequestsException 동시 처리 포화 또는 사용자별 업로드 한도 초과.
     */
    fun extract(file: MultipartFile): ExtractedDocument {
        val userId = TenantContext.requireUserId()
        checkQuota(userId)

        val fileName = file.originalFilename?.trim().orEmpty().ifBlank { "업로드문서" }
        val format = DocumentFormat.ofFileName(fileName)
            ?: throw BadRequestException("지원하지 않는 파일 형식입니다. ${DocumentFormat.SUPPORTED_LABEL} 만 올릴 수 있습니다.")

        val maxBytes = props.maxBytesOf(format.sizeClass)
        if (file.size > maxBytes) {
            throw BadRequestException("${format.name} 파일은 ${maxBytes / (1024 * 1024)}MB 까지 올릴 수 있습니다.")
        }
        if (file.isEmpty) throw BadRequestException("빈 파일입니다.")

        val bytes = file.bytes
        if (!format.matchesMagic(bytes)) {
            throw BadRequestException("파일이 손상되었거나 확장자와 실제 형식이 다릅니다.")
        }

        val extractor = extractors.firstOrNull { it.supports(format) }
            ?: throw BadRequestException("지원하지 않는 파일 형식입니다. ${DocumentFormat.SUPPORTED_LABEL} 만 올릴 수 있습니다.")

        val raw = withSlot {
            try {
                extractor.extract(bytes, fileName, format)
            } catch (e: BadRequestException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // 추출기가 사용자에게 보여줄 목적으로 쓴 메시지 - 그대로 전달.
                throw BadRequestException(e.message ?: "파일을 읽을 수 없습니다.")
            } catch (e: Exception) {
                log.warn("[Document] 추출 실패 name={} format={}: {}", fileName, format, e.message, e)
                throw BadRequestException("파일을 읽는 중 문제가 생겼습니다. 다른 형식으로 저장한 뒤 다시 시도해 주세요.")
            }
        }

        val cleaned = DocumentTextCleaner.clean(raw.text)
        if (cleaned.length < props.minCharCount) {
            throw BadRequestException(
                "텍스트가 없는 스캔(이미지) 문서로 보입니다. 텍스트가 들어 있는 파일로 다시 올리거나 원문을 붙여넣어 주세요. " +
                    "(이미지 문자 인식(OCR)은 지원하지 않습니다.)",
            )
        }

        val truncated = raw.truncated || cleaned.length > props.maxTextLength
        val text = if (cleaned.length > props.maxTextLength) cleaned.take(props.maxTextLength) else cleaned

        log.info(
            "[Document] 추출 완료 name={} format={} bytes={} chars={} pages={} extractor={} truncated={}",
            fileName, format, file.size, text.length, raw.pageCount, extractor.name, truncated,
        )
        return ExtractedDocument(
            fileName = fileName,
            format = format,
            byteSize = file.size,
            text = text,
            charCount = text.length,
            pageCount = raw.pageCount,
            extractor = extractor.name,
            truncated = truncated,
        )
    }

    /** 슬롯을 잡고 실행. 대기가 길어지면 기다리게 두지 않고 429 로 돌려준다(요청이 쌓여 죽는 것보다 낫다). */
    private fun <T> withSlot(block: () -> T): T {
        if (!slots.tryAcquire(props.acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw TooManyRequestsException("문서 처리가 혼잡합니다. 잠시 후 다시 시도해 주세요.")
        }
        try {
            return block()
        } finally {
            slots.release()
        }
    }

    /** 사용자별 시간당 업로드 한도. 인스턴스 메모리 기준(min=0/max=4) - v1 수준의 남용 방어. */
    private fun checkQuota(userId: Long) {
        val now = System.currentTimeMillis()
        val w = buckets.compute(userId) { _, cur ->
            if (cur == null || now - cur.startedAt >= QUOTA_WINDOW_MS) Window(now, 1)
            else cur.also { it.count++ }
        } ?: return
        if (w.count > MAX_UPLOADS_PER_WINDOW) {
            throw TooManyRequestsException("업로드가 너무 잦습니다. 잠시 후 다시 시도해 주세요.")
        }
        if (buckets.size > BUCKET_CAP) buckets.entries.removeIf { now - it.value.startedAt >= QUOTA_WINDOW_MS }
    }

    private companion object {
        const val QUOTA_WINDOW_MS = 60 * 60 * 1000L
        const val MAX_UPLOADS_PER_WINDOW = 60
        const val BUCKET_CAP = 10_000
    }
}
