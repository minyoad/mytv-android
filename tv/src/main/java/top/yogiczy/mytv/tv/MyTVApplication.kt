package top.yogiczy.mytv.tv

import android.app.Application
import top.yogiczy.mytv.core.data.AppData
import top.yogiczy.mytv.core.data.entities.channel.IdGenerator

class MyTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AppData.init(applicationContext)
        UnsafeTrustManager.enableUnsafeTrustManager()

        // 确保 IdGenerator 在主线程第一时间初始化
        IdGenerator.init()
    }
}
