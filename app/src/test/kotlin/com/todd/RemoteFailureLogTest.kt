package com.todd

import com.todd.core.remote.extractWorkflowFailureExcerpt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFailureLogTest {

    @Test
    fun `extractor keeps compiler and Gradle failure evidence`() {
        val log = """
            normal setup line
            > Task :app:compileDebugKotlin FAILED
            e: file:///repo/Test.kt:12:9 Unresolved reference 'missingValue'.
            FAILURE: Build failed with an exception.
            * What went wrong:
            Execution failed for task ':app:compileDebugKotlin'.
            Caused by: java.lang.IllegalStateException: compile failed
            normal cleanup line
        """.trimIndent()

        val excerpt = extractWorkflowFailureExcerpt(log)

        assertTrue(excerpt.contains("Unresolved reference"))
        assertTrue(excerpt.contains("FAILURE:"))
        assertTrue(excerpt.contains("Execution failed for task"))
        assertTrue(excerpt.contains("Caused by:"))
        assertFalse(excerpt.contains("normal setup line"))
    }
}
