package com.aixnative.social.web

import com.aixnative.social.repository.SocialPostRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.Base64

/**
 * 렌더된 카드 이미지 공개 서빙 - 인스타그램 Content Publishing API 가 이미지를 공개 URL 로 fetch 하려면
 * 인증 없이 접근 가능해야 한다. cardnews 경로는 SecurityConfig 에서 permitAll(GET).
 * MVP 는 DB 의 base64 를 디코드해 스트리밍(규모 커지면 GCS 로 이전).
 *  - `/cardnews/{id}.png`         : 표지(첫 슬라이드) - 하위호환
 *  - `/cardnews/{id}/{index}.png` : 캐러셀 index 번째 슬라이드
 */
@RestController
class CardNewsController(
    private val repository: SocialPostRepository,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/cardnews/{id}.png")
    fun cover(@PathVariable id: Long): ResponseEntity<ByteArray> = serve(id, 0)

    @GetMapping("/cardnews/{id}/{index}.png")
    fun slide(@PathVariable id: Long, @PathVariable index: Int): ResponseEntity<ByteArray> = serve(id, index)

    private fun serve(id: Long, index: Int): ResponseEntity<ByteArray> {
        if (index < 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val post = repository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()

        // images_json 이 있으면 그 배열에서, 없으면 단일 image_base64(하위호환, index 0만).
        val b64 = post.imagesJson
            ?.let { runCatching { objectMapper.readValue(it, object : TypeReference<List<String>>() {}) }.getOrNull() }
            ?.getOrNull(index)
            ?: if (index == 0) post.imageBase64 else null
        b64 ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()

        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(bytes)
    }
}
