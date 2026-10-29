package dev.skiphelper

import android.app.Application
import dev.skiphelper.data.SettingsStore
import dev.skiphelper.data.AppIconLoader

class SkipApplication : Application() {
    val store by lazy { SettingsStore(this) }
    val appIcons by lazy { AppIconLoader(this) }
}
