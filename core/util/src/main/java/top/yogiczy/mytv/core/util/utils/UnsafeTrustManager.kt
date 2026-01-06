package top.yogiczy.mytv.core.util.utils

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class UnsafeTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Accept all clients
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Accept all servers
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }

    companion object {
        fun getSSLSocketFactory(): SSLSocketFactory {
            return try {
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, arrayOf<TrustManager>(UnsafeTrustManager()), SecureRandom())
                sslContext.socketFactory
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}