package com.todd

import com.todd.core.security.SigningIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class SigningIdentityTest {
    @Test
    fun `stable Todd fingerprint remains pinned`() {
        assertEquals(
            "2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92",
            SigningIdentity.EXPECTED_STABLE_SHA256
        )
    }
}
