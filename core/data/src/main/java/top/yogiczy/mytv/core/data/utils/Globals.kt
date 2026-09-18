package top.yogiczy.mytv.core.data.utils

import java.io.File

/**
 * 全局变量
 */
object Globals {
    /**
     * 缓存目录
     */
    lateinit var cacheDir: File

    /**
     * 应用版本名（如 2.6.1-beta），由 AppData.init 初始化
     *
     * 取运行时实际安装版本而非 BuildConfig，避免本模块与 app 模块版本号不同步；
     * 未初始化时为 unknown，调用方无需判空。
     */
    var appVersionName: String = "unknown"
}