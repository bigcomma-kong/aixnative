package com.aixnative.social

import com.aixnative.social.service.CommunityHotSource
import com.aixnative.social.service.ScrapingProxy
import com.aixnative.social.service.SocialProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.assertTrue

/**
 * 커뮤니티 핫글 소스의 on/off 게이트 검증.
 * 핵심은 "결과가 비었다"가 아니라 **스크래핑이 아예 나가지 않는다**는 것 - 프록시 호출은
 * 유료 크레딧과 실행시간을 쓰므로, 꺼진 상태에서 목록만 훑고 버리면 끈 의미가 없다.
 */
class CommunityHotSourceTest {

    private val targets = listOf(
        SocialProperties.CommunityTarget("에펨코리아 포텐", "https://www.fmkorea.com/best"),
        SocialProperties.CommunityTarget("더쿠 핫게시판", "https://theqoo.net/hot"),
    )

    @Test
    fun `disabled flag skips scraping entirely even when targets are configured`() {
        val proxy = mock(ScrapingProxy::class.java)
        val source = CommunityHotSource(
            SocialProperties(communityStoryTargets = targets, communityStoryEnabled = false),
            proxy,
        )

        assertTrue(source.produce().isEmpty())
        verifyNoInteractions(proxy) // 대상이 설정돼 있어도 프록시를 건드리면 안 된다.
    }

    @Test
    fun `empty target list also skips scraping`() {
        val proxy = mock(ScrapingProxy::class.java)
        val source = CommunityHotSource(
            SocialProperties(communityStoryTargets = emptyList(), communityStoryEnabled = true),
            proxy,
        )

        assertTrue(source.produce().isEmpty())
        verifyNoInteractions(proxy)
    }

    @Test
    fun `enabled with targets does reach the proxy`() {
        val proxy = mock(ScrapingProxy::class.java) // fetch() 는 기본 null 반환 → graceful 빈결과
        val source = CommunityHotSource(
            SocialProperties(communityStoryTargets = targets, communityStoryEnabled = true),
            proxy,
        )

        // 게이트가 열리면 대상 수만큼 스크랩을 시도한다(응답이 null 이라 결과는 비지만 호출은 발생).
        assertTrue(source.produce().isEmpty())
        org.mockito.Mockito.verify(proxy, org.mockito.Mockito.times(targets.size)).fetch(org.mockito.ArgumentMatchers.anyString())
    }
}
