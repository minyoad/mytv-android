package top.yogiczy.mytv.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import top.yogiczy.mytv.core.data.AppData
import top.yogiczy.mytv.tv.BuildConfig
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.tv.diagnostics.AppPerformance

class MyTVApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppPerformance.start()

        AppData.init(applicationContext)
        UnsafeTrustManager.enableUnsafeTrustManager()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // DebugLogger 会在 release 包打印全量加载日志（占主线程时间且刷爆 logcat），
            // 仅 debug 包启用
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .components {
                    add(ImageDecoderDecoder.Factory() as Decoder.Factory)
            }
            .okHttpClient(OkHttp.client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // 升级保留：使用 filesDir 下的 cache（AppData.init 已配置并自动从旧 cacheDir 迁移）
                    .directory(Globals.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.1) // 增加到 10%
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}
