package top.yogiczy.mytv.core.data.network

import okhttp3.OkHttpClient
import top.yogiczy.mytv.core.util.utils.UnsafeTrustManager
import java.util.concurrent.TimeUnit

object OkHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
