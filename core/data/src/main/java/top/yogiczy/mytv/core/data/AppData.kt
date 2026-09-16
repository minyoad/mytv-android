package top.yogiczy.mytv.core.data

import android.content.Context
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.data.utils.SP
import java.io.File

object AppData {
    private val log = Logger.create(AppData::class.java.simpleName)

    fun init(context: Context) {
        // 关键：使用 filesDir/cache 而不是 context.cacheDir，
        // 升级（覆盖安装）时 cacheDir 常被系统清空，filesDir 不会。
        val targetDir = File(context.filesDir, "cache")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        // 首次启动时把旧 cacheDir 里残留的文件迁移过来，避免升级后立刻全量重新下载
        runCatching { migrateLegacyCacheDir(context, targetDir) }
            .onFailure { log.e("迁移旧缓存失败", it) }

        Globals.cacheDir = targetDir
        SP.init(context)
    }

    private fun migrateLegacyCacheDir(context: Context, target: File) {
        val legacy = context.cacheDir
        if (!legacy.exists() || legacy.absolutePath == target.absolutePath) return

        val children = legacy.listFiles() ?: return
        if (children.isEmpty()) return

        log.i("迁移旧缓存: ${legacy.absolutePath} -> ${target.absolutePath}, ${children.size} 项")
        for (file in children) {
            try {
                val dest = File(target, file.name)
                if (dest.exists()) continue
                if (file.isDirectory) {
                    file.copyRecursively(dest, overwrite = false)
                } else {
                    file.copyTo(dest, overwrite = false)
                }
            } catch (e: Exception) {
                log.w("迁移文件失败: ${file.name}", e)
            }
        }
    }
}