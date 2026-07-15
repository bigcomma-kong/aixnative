package com.aixnative.social.service

/**
 * AI 이미지 생성 엔진 - 프롬프트 → 이미지(base64). 스토리 장면 이미지를 만든다.
 * 구현체(GeminiImageClient 등)는 @Component 로 등록하면 [StoryImageComposer] 가
 * `List<ImageEngine>` 로 주입받아 설정된 것을 쓴다(없거나 실패면 타이포 폴백, graceful).
 *
 * 텍스트 라우터([com.aixnative.ai.domain.AiProvider])와 별개 - AiServiceManager 에 잡히면 안 되므로
 * AiProvider 를 구현하지 않는다. 배경 운영비(사용자 크레딧 미차감).
 */
interface ImageEngine {
    val name: String

    fun isConfigured(): Boolean

    /**
     * @param prompt 이미지 생성 프롬프트(영어 권장)
     * @param aspectRatio "4:5" 등 종횡비 힌트
     * @return 프리픽스 없는 base64 이미지(PNG/JPEG) 또는 null(미설정/실패, graceful).
     */
    fun generate(prompt: String, aspectRatio: String = "4:5"): String?
}
