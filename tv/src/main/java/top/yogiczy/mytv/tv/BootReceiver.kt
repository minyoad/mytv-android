package top.yogiczy.mytv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.yogiczy.mytv.core.data.utils.Logger

import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.utils.Configs

/**
 * 开机自启动监听
 */
class BootReceiver : BroadcastReceiver() {
    private val log = Logger.create(javaClass.simpleName)

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            log.d("onReceive")
            val sp = SP.getInstance(context)
            val bootLaunch = sp.getBoolean(Configs.KEY.APP_BOOT_LAUNCH.name, false)
            log.d("bootLaunch=$bootLaunch")
            if (bootLaunch) {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}
