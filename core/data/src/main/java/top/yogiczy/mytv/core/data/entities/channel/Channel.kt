package top.yogiczy.mytv.core.data.entities.channel

import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicInteger

object IdGenerator {
    private val counter = AtomicInteger(-1)

    fun generateId(): String = counter.incrementAndGet().toString()

    /** 强制提前初始化，保证单例在 Application 阶段就建好 */
    fun init() { /* no-op */ }
}
/**
 * 频道
 */
@Immutable
data class Channel(
    /**
     * 频道ID
     */
    val id: String = IdGenerator.generateId(), // 使用序号生成 ID
    /**
     * 频道名称
     */
    val name: String = "",

    /**
     * 节目单名称，用于查询节目单
     */
    val epgName: String = "",

    /**
     * 播放地址
     */
    val urlList: List<String> = listOf("http://1.2.3.4"),

    /**
     * 台标
     */
    val logo: String? = null,
) {
    companion object {
        val EXAMPLE = Channel(
            name = "CCTV-1 综合",
            epgName = "cctv1",
            urlList = listOf(
                "http://dbiptv.sn.chinamobile.com/PLTV/88888890/224/3221226231/index.m3u8",
                "http://[2409:8087:5e01:34::20]:6610/ZTE_CMS/00000001000000060000000000000131/index.m3u8?IAS",
            ),
            logo = "https://live.fanmingming.com/tv/CCTV1.png"
        )
    }
}