package dev.skiphelper.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-only, bounded process cache. Never keeps an Activity or preloads installed apps. */
class AppIconLoader(context: Context) {
    private val packages = context.applicationContext.packageManager
    private val cache = LruCache<String, ImageBitmap>(96)
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)

    suspend fun load(packageName: String): ImageBitmap? = withContext(dispatcher) {
        cache.get(packageName) ?: try {
            packages.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap().also { cache.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null // App may have been removed while the launcher list was displayed.
        } catch (_: android.content.res.Resources.NotFoundException) {
            null // Keep the visible initial-letter placeholder for broken icon resources.
        }
    }
}
