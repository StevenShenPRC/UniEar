package uniear.core

import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

object Translations {
    val languageChoices = linkedMapOf(
        "en" to "English", "ar" to "العربية", "es" to "Español",
        "fr" to "Français", "ru" to "Русский", "zh-Hans" to "简体中文",
    )
    internal val supportedLanguages = languageChoices.keys.map { Locale.forLanguageTag(it).language }.toSet()
    private val catalogs = supportedLanguages.associateWith { language ->
        lazy { load(if (language == "en") "messages.properties" else "messages_$language.properties") }
    }

    @Volatile
    private var preferredLocale: Locale? = null

    fun setLocale(locale: Locale?) {
        preferredLocale = locale
    }

    val locale: Locale get() = preferredLocale ?: Locale.getDefault()

    internal fun forLocale(locale: Locale): ResourceBundle =
        catalogs.getValue(locale.language.takeIf(catalogs::containsKey) ?: "en").value

    private fun load(name: String): ResourceBundle = requireNotNull(
        Translations::class.java.getResourceAsStream("/uniear/core/$name"),
    ).reader(Charsets.UTF_8).use(::PropertyResourceBundle)

    internal fun text(key: String, arguments: Array<out Any?>): String {
        val catalog = forLocale(locale)
        val template = if (catalog.containsKey(key)) catalog.getString(key) else catalogs.getValue("en").value.getString(key)
        return if (arguments.isEmpty()) template else placeholder.replace(template) { match ->
            arguments[match.groupValues[1].toInt()].toString()
        }
    }

    // Android's ICU regex engine requires both braces to be escaped.
    private val placeholder = Regex("\\{([0-9]+)\\}")
}

fun tr(key: String, vararg arguments: Any?): String = Translations.text(key, arguments)
