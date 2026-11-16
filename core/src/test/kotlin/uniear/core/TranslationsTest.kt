package uniear.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationsTest {
    @Test
    fun catalogsHaveTheSameKeysAndArguments() {
        val english = Translations.forLocale(Locale.ENGLISH)
        val chinese = Translations.forLocale(Locale.SIMPLIFIED_CHINESE)
        val placeholder = Regex("\\{[0-9]+}")
        assertEquals(english.keySet(), chinese.keySet())
        for (key in english.keySet()) {
            val expected = placeholder.findAll(english.getString(key)).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(chinese.getString(key)).map { it.value }.sorted().toList()
            assertEquals(key, expected, actual)
        }
    }

    @Test
    fun unsupportedLocaleFallsBackToEnglish() {
        assertEquals("Settings", Translations.forLocale(Locale.FRENCH).getString("settings"))
        assertEquals("设置", Translations.forLocale(Locale.SIMPLIFIED_CHINESE).getString("settings"))
    }
}
