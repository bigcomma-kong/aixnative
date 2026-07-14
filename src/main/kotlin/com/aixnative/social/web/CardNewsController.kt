package com.aixnative.social.web

import com.aixnative.social.repository.SocialPostRepository
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
 */
@RestController
class CardNewsController(
    private val repository: SocialPostRepository,
) {
    @GetMapping("/cardnews/{id}.png")
    fun image(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val post = repository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val b64 = post.imageBase64
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(bytes)
    }
}
