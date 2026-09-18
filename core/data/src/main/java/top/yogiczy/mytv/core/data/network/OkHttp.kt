package top.yogiczy.mytv.core.data.network

import android.os.Build
import okhttp3.OkHttpClient
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.util.utils.UnsafeTrustManager
import java.util.concurrent.TimeUnit

object OkHttp {
    /**
     * 自定义 User-Agent（包含 mytv 标识与应用版本号），统一附加到未显式指定 UA 的请求
     *
     * 示例：MyTV-android/2.6.1-beta (okhttp; Android 33; Xiaomi MiTV)
     *
     * 版本号取自运行时实际安装版本（Globals.appVersionName），且不使用 by lazy 缓存，
     * 以避免初始化早于 AppData.init 时固化成 unknown。
     */
    val USER_AGENT: String
        get() = "MyTV-android/${Globals.appVersionName} " +
            "(okhttp; Android ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL})"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                // 仅在请求未显式设置 User-Agent 时附加自定义 UA
                val newRequest = if (request.header("User-Agent") == null) {
                    request.newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(
                UnsafeTrustManager.getSSLSocketFactory(),
                UnsafeTrustManager()
            )
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
