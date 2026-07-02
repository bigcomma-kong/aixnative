package com.aixnative.lead.repository

import com.aixnative.lead.domain.Lead
import org.springframework.data.jpa.repository.JpaRepository

interface LeadRepository : JpaRepository<Lead, Long> {
    /** 같은 (이메일, 유입 도구) 조합은 한 번만 기록(중복 무시). */
    fun existsByEmailAndSource(email: String, source: String): Boolean
}
