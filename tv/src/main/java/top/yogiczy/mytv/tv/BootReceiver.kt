package top.yogiczy.mytv.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.utils.Configs


/**
 * 开机自启动监听
 */
class BootReceiver : BroadcastReceiver() {
    var log = Logger.create(javaClass.simpleName)
    override fun onReceive(context: Context, intent: Intent) {
        log.d("onReceive: ${intent.action}")
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val sp = SP.getInstance(context)
            val bootLaunch = sp.getBoolean(Configs.KEY.APP_BOOT_LAUNCH.name, false)
            log.d("bootLaunch: $bootLaunch")
            if (bootLaunch) {

//                val jobScheduler =
//                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
//                val jobInfo = JobInfo.Builder(1, ComponentName(context, MyJobService::class.java))
//                    .setMinimumLatency(10000) // 延迟1秒执行
//                    .build()
//                jobScheduler.schedule(jobInfo)

//                 启动前台服务
//                val serviceIntent = Intent(context, MyForegroundService::class.java)
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    context.startForegroundService(serviceIntent)
//                } else {
//                    context.startService(serviceIntent)
//                }

//                context.startActivity(Intent(context, MainActivity::class.java).apply {
//                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                })


                val alarmIntent = Intent(
                    context,
                    AlarmReceiver::class.java
                )
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                // 设置一个 5 秒后触发的 Alarm
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 5000,
                    pendingIntent
                )
            }
        }
    }

}



