package com.todd

import com.todd.core.agent.ToddPromptSelector
import com.todd.core.agent.ToddPromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToddPromptLibraryTest {
    private val examples = listOf(
        ToddPromptTemplate("pc-1", "mobile", "Android Engineer", "Diagnose Android UI layout and keyboard insets carefully."),
        ToddPromptTemplate("pc-2", "coding", "Software Debugger", "Find the root cause and test minimal changes."),
        ToddPromptTemplate("pc-3", "research", "Fact Checker", "Compare reliable primary sources."),
        ToddPromptTemplate("pc-4", "translation", "Language Translator", "Translate precisely without omissions.")
    )

    @Test fun parseVendoredTsv() {
        val parsed = ToddPromptSelector.parse(
            "# CC0\npc-1\tmobile\tAndroid Engineer\tFirst line\\nSecond line\n"
        )
        assertEquals(1, parsed.size)
        assertEquals("First line\nSecond line", parsed.single().prompt)
    }

    @Test fun arabicAndroidRequestSelectsMobileTemplate() {
        assertEquals("pc-1", ToddPromptSelector.select("أصلح واجهة تطبيق أندرويد", examples)?.id)
    }

    @Test fun englishCodeRequestSelectsCodingTemplate() {
        assertEquals("pc-2", ToddPromptSelector.select("Please debug this code", examples)?.id)
    }

    @Test fun unknownTopicDoesNotInjectArbitraryInstructions() {
        assertNull(ToddPromptSelector.select("صباح الخير", examples))
    }

    @Test fun arabicResearchAndTranslationAreSupported() {
        assertEquals("pc-3", ToddPromptSelector.select("ابحث وتحقق من المصادر", examples)?.id)
        assertTrue(ToddPromptSelector.select("ترجمة النص", examples)?.category == "translation")
    }
}
