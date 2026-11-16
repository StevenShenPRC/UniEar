package uniear.android

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import uniear.core.Translations

class UniEarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        updateLocale(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLocale(newConfig)
        ControlState.refreshSurfaces(this)
    }

    private fun updateLocale(configuration: Configuration) {
        val appLocale = if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)
        } else null
        Translations.setLocale(appLocale ?: configuration.locales[0])
    }
}
