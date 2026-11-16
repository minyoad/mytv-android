package top.yogiczy.mytv.tv.ui.screens.channel.components

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

/** Prefetch and display must use the same size/cache key, rather than decoding full-size logos twice. */
internal object ChannelLogoRequest {
    fun create(context: Context, url: String): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey("channel-logo:128:$url")
        .size(128)
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(false)
        .build()
}
