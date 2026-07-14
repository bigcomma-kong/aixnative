package com.aixnative.social.service

import com.aixnative.social.domain.RankSlide
import com.aixnative.social.domain.SocialMediaType
import com.aixnative.social.domain.SocialPost
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 카드 이미지 렌더러(IMAGE) - Node satori 스크립트(render/render-card.mjs)를 프로세스로 호출한다.
 * stdin 으로 {topic,title,slides} JSON 을 넘기고 stdout 으로 PNG base64 를 받는다.
 * node 미설치/스크립트 부재/타임아웃 시 예외 → 오케스트레이터가 DRAFT 로 남긴다(graceful).
 */
@Component
class ImageCardRenderer(
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
) : MediaRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    override val mediaType = SocialMediaType.IMAGE

    override fun renderBase64(post: SocialPost): String {
        val slides = post.slidesJson
            ?.let { runCatching { objectMapper.readValue(it, object : TypeReference<List<RankSlide>>() {}) }.getOrNull() }
            .orEmpty()
        require(slides.isNotEmpty()) { "렌더할 슬라이드가 없습니다." }

        val payload = mapOf(
            "topic" to post.topic,
            "title" to post.title,
            "slides" to slides.map {
                mapOf("rank" to it.rank, "title" to it.title, "summary" to it.summary, "sourceName" to it.sourceName)
            },
        )
        val jsonBytes = objectMapper.writeValueAsBytes(payload)

        val script = File(props.render.scriptPath)
        val proc = ProcessBuilder(props.render.nodeBin, script.absolutePath)
            .redirectErrorStream(false)
            .start()

        // stdin 은 작으므로(수 KB) 먼저 다 쓰고 닫는다 → 이후 stdout(EOF까지) 읽어 데드락 회피.
        proc.outputStream.use { it.write(jsonBytes) }
        val outBytes = proc.inputStream.readBytes()

        val finished = proc.waitFor(props.render.timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw RuntimeException("카드 렌더 타임아웃(${props.render.timeoutMs}ms)")
        }
        if (proc.exitValue() != 0) {
            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8).take(500)
            throw RuntimeException("카드 렌더 실패(exit=${proc.exitValue()}): $err")
        }
        val b64 = outBytes.toString(Charsets.UTF_8).trim()
        log.info("[social] 카드 렌더 완료 id={} ({} bytes b64)", post.id, b64.length)
        return b64
    }
}
