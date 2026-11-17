package uniear.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationsTest {
    @Test
    fun officialLanguagesHaveTheSameKeysAndArguments() {
        val english = Translations.forLocale(Locale.ENGLISH)
        val placeholder = Regex("\\{[0-9]+\\}")
        for (language in Translations.supportedLanguages - "en") {
            val translated = Translations.forLocale(Locale.forLanguageTag(language))
            assertEquals(language, english.keySet(), translated.keySet())
            for (key in english.keySet()) {
                val expected = placeholder.findAll(english.getString(key)).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(translated.getString(key)).map { it.value }.sorted().toList()
                assertEquals("$language/$key", expected, actual)
            }
        }
    }

    @Test
    fun unsupportedLocaleFallsBackToEnglish() {
        assertEquals("Settings", Translations.forLocale(Locale.JAPANESE).getString("settings"))
        assertEquals("设置", Translations.forLocale(Locale.SIMPLIFIED_CHINESE).getString("settings"))
    }
}
