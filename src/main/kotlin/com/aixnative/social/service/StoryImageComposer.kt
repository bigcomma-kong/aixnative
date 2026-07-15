package com.aixnative.social.service

import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.StoryScript
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 스토리 장면 이미지 생성 - slides_json(StoryScript)의 각 장면 imagePrompt 를 [ImageEngine] 으로
 * 생성해 imageB64 를 채운 뒤 slides_json 을 재직렬화한다(post 변경, 저장은 호출부).
 *
 * [MarketBriefingGenerator] 소비 패턴(client 직접 주입, 미설정 시 skip)과 동일하게 graceful:
 * 엔진이 없거나(키 미설정) 개별 장면 실패 시 imageB64=null 유지 → 렌더러가 타이포 폴백.
 * 배경 운영비(사용자 크레딧 미차감).
 */
@Component
class StoryImageComposer(
    private val imageEngines: List<ImageEngine>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** post.slidesJson(StoryScript)의 장면들에 생성 이미지를 채운다. 실패 장면은 null 유지. */
    fun compose(post: SocialPost) {
        val engine = imageEngines.firstOrNull { it.isConfigured() }
        if (engine == null) {
            log.info("[social][story] 이미지 엔진 미설정 - 전 장면 타이포 폴백 id={}", post.id)
            return
        }
        val json = post.slidesJson ?: return
        val script = runCatching { objectMapper.readValue(json, StoryScript::class.java) }.getOrNull() ?: return

        var made = 0
        val filled = script.scenes.map { scene ->
            if (!scene.imageB64.isNullOrBlank()) return@map scene
            val b64 = runCatching { engine.generate(scene.imagePrompt) }.getOrNull()
            if (b64 != null) made++
            scene.copy(imageB64 = b64)
        }
        post.slidesJson = objectMapper.writeValueAsString(script.copy(scenes = filled))
        log.info("[social][story] 장면 이미지 생성 id={} {}/{} ({})", post.id, made, filled.size, engine.name)
    }
}
