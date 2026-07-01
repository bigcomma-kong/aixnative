package com.aixnative.ai

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiToolRunService

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiToolRunServiceTest(
    @Autowired private val service: AiToolRunService,
) {

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private fun asTenant(tenantId: Long, userId: Long) =
        TenantContext.set(TenantContext.Current(tenantId, userId, "u$userId@example.com"))

    @Test
    fun `run is recorded under the current tenant and listed back`() {
        asTenant(1L, 1L)
        service.record(tool = "UNDERWRITING_NARRATIVE", status = RunStatus.SUCCESS)
        assertEquals(1, service.listMine().size)
    }

    @Test
    fun `another tenant cannot read a run (IDOR blocked)`() {
        asTenant(1L, 1L)
        val saved = service.record(tool = "UNDERWRITING_NARRATIVE", status = RunStatus.SUCCESS)

        asTenant(2L, 2L)
        assertFailsWith<NotFoundException> { service.get(saved.id!!) }
        assertEquals(0, service.listMine().size)
    }
}
