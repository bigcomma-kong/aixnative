package com.aixnative.marketfeed.repository

import org.springframework.data.jpa.repository.JpaRepository
import com.aixnative.marketfeed.domain.MarketBriefing

interface MarketBriefingRepository : JpaRepository<MarketBriefing, Long> {

    /** 가장 최근 생성된 브리핑 1건(시장 탭 상단 노출). */
    fun findTopByOrderByGeneratedAtDesc(): MarketBriefing?

    /** 지난 브리핑 아카이브(최신순, 최대 30건). */
    fun findTop30ByOrderByGeneratedAtDesc(): List<MarketBriefing>
}
