package com.aixnative.social.service

import com.aixnative.social.domain.RankSlide
import com.aixnative.social.domain.SocialMediaType
import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SocialPostKind
import com.aixnative.social.domain.StoryScript
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 카드 이미지 렌더러(IMAGE) - Node satori 스크립트(render/render-card.mjs)를 프로세스로 호출한다.
 * stdin 으로 {topic,title,slides[imageUrl 포함]} JSON 을 넘기고 stdout 으로 **base64 PNG 배열(JSON)** 을 받는다.
 * (표지 1장 + 항목별 1장). node 미설치/스크립트 부재/타임아웃 시 예외 → 오케스트레이터가 DRAFT 로 남긴다(graceful).
 */
@Component
class ImageCardRenderer(
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
) : MediaRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    override val mediaType = SocialMediaType.IMAGE

    override fun renderSlides(post: SocialPost): List<String> {
        val payload = if (post.kind == SocialPostKind.STORY) storyPayload(post) else rankingPayload(post)
        val jsonBytes = objectMapper.writeValueAsBytes(payload)

        val script = File(props.render.scriptPath)
        val proc = ProcessBuilder(props.render.nodeBin, script.absolutePath)
            .redirectErrorStream(false)
            .start()

        // stdin 은 작으므로(수 KB) 먼저 다 쓰고 닫는다 → 이후 stdout(EOF까지) 읽어 데드락 회피.
        proc.outputStream.use { it.write(jsonBytes) }
        val outBytes = proc.inputStream.readBytes()

        // 원격 이미지 프리페치가 있어 단일 렌더보다 여유롭게 - 타임아웃은 props.render.timeoutMs 사용.
        val finished = proc.waitFor(props.render.timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw RuntimeException("카드 렌더 타임아웃(${props.render.timeoutMs}ms)")
        }
        if (proc.exitValue() != 0) {
            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8).take(500)
            throw RuntimeException("카드 렌더 실패(exit=${proc.exitValue()}): $err")
        }
        val out = outBytes.toString(Charsets.UTF_8).trim()
        val pages: List<String> = runCatching {
            objectMapper.readValue(out, object : TypeReference<List<String>>() {})
        }.getOrElse { throw RuntimeException("카드 렌더 출력 파싱 실패: ${out.take(200)}") }
        require(pages.isNotEmpty()) { "렌더 결과가 비었습니다." }
        log.info("[social] 카드 렌더 완료 id={} kind={} slides={}", post.id, post.kind, pages.size)
        return pages
    }

    /** RANKING - slides_json=RankSlide[]. mode=ranking. */
    private fun rankingPayload(post: SocialPost): Map<String, Any?> {
        val slides = post.slidesJson
            ?.let { runCatching { objectMapper.readValue(it, object : TypeReference<List<RankSlide>>() {}) }.getOrNull() }
            .orEmpty()
        require(slides.isNotEmpty()) { "렌더할 슬라이드가 없습니다." }
        return mapOf(
            "mode" to "ranking",
            "topic" to post.topic,
            "title" to post.title,
            "slides" to slides.map {
                mapOf(
                    "rank" to it.rank, "title" to it.title, "summary" to it.summary,
                    "sourceName" to it.sourceName, "imageUrl" to it.imageUrl,
                )
            },
        )
    }

    /** STORY - slides_json=StoryScript. mode=story(표지+장면+아웃트로). */
    private fun storyPayload(post: SocialPost): Map<String, Any?> {
        val script = post.slidesJson
            ?.let { runCatching { objectMapper.readValue(it, StoryScript::class.java) }.getOrNull() }
        require(script != null && script.scenes.isNotEmpty()) { "렌더할 스토리 장면이 없습니다." }
        return mapOf(
            "mode" to "story",
            "coverTitle" to post.title,
            "engagement" to post.engagement,
            "sourceBoard" to post.sourceBoard,
            "scenes" to script.scenes.map {
                mapOf("caption" to it.caption, "imageUrl" to it.imageUrl, "imageB64" to it.imageB64)
            },
            "outro" to script.outro,
        )
    }
}
