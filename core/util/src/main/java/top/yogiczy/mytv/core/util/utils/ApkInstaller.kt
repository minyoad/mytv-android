package top.yogiczy.mytv.core.util.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    private const val TAG = "ApkInstaller"

    fun installApk(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            // 修复：之前静默失败，导致用户以为"下载完成但没触发安装"，实际是文件丢失。
            // 这里打日志方便排查，调用方应已做"文件存在性"判断。
            Log.w(TAG, "APK 文件不存在: $filePath")
            return
        }

        // 修复：直接使用源 APK 文件（FileProvider 已通过 file_paths.xml 暴露 filesDir），
        // 避免原方案中将 50~80MB APK 通过 writeBytes(readBytes()) 二次复制到 cacheDir，
        // 既浪费内存，又可能被系统在内存压力下清理导致 FileProvider 抛异常。
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.FileProvider", file
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            setDataAndType(uri, "application/vnd.android.package-archive")
        }

        context.startActivity(installIntent)
    }
}