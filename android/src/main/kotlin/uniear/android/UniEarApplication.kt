package uniear.android

import android.app.Application
import android.content.res.Configuration

class UniEarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.update(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.update(this, newConfig)
        ControlState.refreshSurfaces(this)
    }

}
