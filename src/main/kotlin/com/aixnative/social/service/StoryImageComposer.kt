package com.aixnative.social.service

import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.StoryScene
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
        val engines = imageEngines.filter { it.isConfigured() }
        if (engines.isEmpty()) {
            log.info("[social][story] 이미지 엔진 미설정 - 전 장면 타이포 폴백 id={}", post.id)
            return
        }
        val json = post.slidesJson ?: return
        val script = runCatching { objectMapper.readValue(json, StoryScript::class.java) }.getOrNull() ?: return

        var made = 0
        val filled = script.scenes.map { scene ->
            if (!scene.imageUrl.isNullOrBlank() || !scene.imageB64.isNullOrBlank()) return@map scene
            fillScene(scene).also { if (it !== scene) made++ }
        }
        post.slidesJson = objectMapper.writeValueAsString(script.copy(scenes = filled))
        log.info("[social][story] 장면 이미지 생성 id={} {}/{} (엔진 {})", post.id, made, filled.size, engines.joinToString(",") { it.name })
    }

    /**
     * 장면 1개를 [Order] 순 엔진으로 채운다 - 앞선 엔진(Gemini 생성) 실패 시 다음 엔진(스톡)으로 폴백.
     * 엔진별: imageUrl(스톡, 렌더 시점 fetch·경량) 우선 → generate(생성형 base64). 전부 실패면 원본(null 유지 → 타이포).
     */
    private fun fillScene(scene: StoryScene): StoryScene {
        for (engine in engines()) {
            runCatching { engine.imageUrl(scene.imagePrompt) }.getOrNull()?.let {
                return scene.copy(imageUrl = it)
            }
            runCatching { engine.generate(scene.imagePrompt) }.getOrNull()?.let {
                return scene.copy(imageB64 = it)
            }
        }
        return scene
    }

    private fun engines() = imageEngines.filter { it.isConfigured() }
}
