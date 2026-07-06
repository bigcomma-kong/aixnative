package com.aixnative.property.domain

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

/** 임대차 일정 이벤트 유형 - 캘린더·리스크·리마인더 공통. */
enum class LeaseEventType {
    /** 계약 만기. */
    EXPIRY,

    /** 임대료 인상 예정. */
    ESCALATION,

    /** 렌트프리 종료(임대료 발생 시작). */
    RENT_FREE_END,
}

/**
 * 리마인더 이메일 발송 로그 - (임대차·이벤트·기준일) 단위 멱등 키. 크론이 같은 만기 알림을
 * 매일 중복 발송하지 않도록, 발송 전에 이 로그 존재 여부를 확인한다. 테넌트 스코프.
 */
@Entity
@Table(name = "lease_reminder_sent")
class LeaseReminderSent(
    @Column(name = "lease_id", nullable = false)
    var leaseId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    var eventType: LeaseEventType,

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,

    @Column(name = "sent_at", nullable = false)
    var sentAt: java.time.Instant,
) : BaseTenantEntity()
