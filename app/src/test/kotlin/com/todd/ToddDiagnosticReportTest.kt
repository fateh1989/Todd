package com.todd

import com.todd.core.diagnostics.DiagnosticCheck
import com.todd.core.diagnostics.DiagnosticStatus
import com.todd.core.diagnostics.ToddDiagnosticReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToddDiagnosticReportTest {

    @Test
    fun `full Todd readiness requires all critical checks to pass`() {
        val ids = listOf(
            "stable-signing", "memory", "cloud", "voice-runtime", "github",
            "accessibility", "overlay", "microphone", "notifications", "keyboard"
        )
        val ready = ToddDiagnosticReport(
            checks = ids.map {
                DiagnosticCheck(it, it, DiagnosticStatus.PASS, "ok")
            }
        )
        assertTrue(ready.isFullToddReady)

        val blocked = ToddDiagnosticReport(
            checks = ready.checks.map {
                if (it.id == "stable-signing") it.copy(status = DiagnosticStatus.WARN) else it
            }
        )
        assertFalse(blocked.isFullToddReady)
        assertTrue(blocked.blockingChecks.any { it.id == "stable-signing" })
    }
}
