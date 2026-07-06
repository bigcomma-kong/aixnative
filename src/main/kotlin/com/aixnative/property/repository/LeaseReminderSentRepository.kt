package com.aixnative.property.repository

import com.aixnative.property.domain.LeaseEventType
import com.aixnative.property.domain.LeaseReminderSent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface LeaseReminderSentRepository : JpaRepository<LeaseReminderSent, Long> {
    /** 멱등 확인 - 같은 (임대차·이벤트·기준일) 알림을 이미 발송했는지. */
    fun existsByLeaseIdAndEventTypeAndDueDate(
        leaseId: Long,
        eventType: LeaseEventType,
        dueDate: LocalDate,
    ): Boolean

    /** 계정/건물 삭제 정리용. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
