package uniear.core

import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

object Translations {
    private val english by lazy { load("messages.properties") }
    private val chinese by lazy { load("messages_zh.properties") }

    @Volatile
    private var preferredLocale: Locale? = null

    fun setLocale(locale: Locale?) {
        preferredLocale = locale
    }

    internal fun forLocale(locale: Locale): ResourceBundle =
        if (locale.language == Locale.CHINESE.language) chinese else english

    private fun load(name: String): ResourceBundle = requireNotNull(
        Translations::class.java.getResourceAsStream("/uniear/core/$name"),
    ).reader(Charsets.UTF_8).use(::PropertyResourceBundle)

    internal fun text(key: String, arguments: Array<out Any?>): String {
        val template = forLocale(preferredLocale ?: Locale.getDefault()).getString(key)
        return if (arguments.isEmpty()) template else placeholder.replace(template) { match ->
            arguments[match.groupValues[1].toInt()].toString()
        }
    }

    private val placeholder = Regex("\\{([0-9]+)}")
}

fun tr(key: String, vararg arguments: Any?): String = Translations.text(key, arguments)
