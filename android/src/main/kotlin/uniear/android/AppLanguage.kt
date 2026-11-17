package uniear.android

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import uniear.core.Translations
import java.util.Locale

internal object AppLanguage {
    private const val PREFS = "app-language"
    private const val TAG = "tag"

    fun selected(context: Context): Locale? = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) null else locales[0]
    } else {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TAG, null)?.let(Locale::forLanguageTag)
    }

    fun update(context: Context, configuration: Configuration = context.resources.configuration) {
        Translations.setLocale(selected(context) ?: configuration.locales[0])
    }

    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TAG, tag).apply()
        }
    }

    fun localize(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val locale = selected(context) ?: return context
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }
}
