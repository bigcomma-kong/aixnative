package com.aixnative.property.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 자산관리(PM) 리마인더 설정. 크론(Cloud Scheduler)이 토큰 보호 엔드포인트를 호출하면,
 * 만기·인상 예정일이 [reminderLeadDays] 일 이내로 다가온 임대차에 대해 소유자에게 이메일을 발송한다.
 * 인증 토큰은 기존 마켓피드 수집 토큰([com.aixnative.marketfeed.service.MarketFeedProperties.ingestToken])을
 * 재사용한다(신규 시크릿 불필요).
 */
@ConfigurationProperties(prefix = "property")
data class PropertyProperties(
    /** 이 일수 이내로 다가온 만기/인상/렌트프리 종료에 대해 리마인더 발송(기본 30일). */
    val reminderLeadDays: Long = 30,
    /** 리마인더 발송 기능 on/off(기본 on - 토큰이 없으면 엔드포인트 자체가 비활성). */
    val reminderEnabled: Boolean = true,
)
