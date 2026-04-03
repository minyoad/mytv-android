package top.yogiczy.mytv.core.data.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ChannelUtil {
    private fun standardCctvChannelNameTest(keys: List<List<String>>): (String) -> Boolean {
        return { name: String -> keys.any { it.all { word -> word.lowercase() in name.lowercase() } } }
    }

    private val standardChannelNameTest: Map<String, (String) -> Boolean> = mapOf(
        "CCTV-5+赛事" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "5+"),
                listOf("cctv", "5plus"),
                listOf("cctv", "体育"),
                listOf("中央", "5+"),
                listOf("中央", "五+"),
            )
        ),
        "CCTV-10科教" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "10"),
                listOf("cctv", "科教"),
                listOf("中央", "10"),
                listOf("中央", "十"),
            )
        ),
        "CCTV-11戏曲" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "11"),
                listOf("cctv", "戏曲"),
                listOf("中央", "11"),
                listOf("中央", "十一"),
            )
        ),
        "CCTV-12社法" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "12"),
                listOf("cctv", "社法"),
                listOf("cctv", "法治"),
                listOf("cctv", "法制"),
                listOf("cctv", "社会与法"),
                listOf("中央", "12"),
                listOf("中央", "十二"),
            )
        ),
        "CCTV-13新闻" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "13"),
                listOf("cctv", "新闻"),
                listOf("中央", "13"),
                listOf("中央", "十三"),
            )
        ),
        "CCTV-14少儿" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "14"),
                listOf("cctv", "少儿"),
                listOf("中央", "14"),
                listOf("中央", "十四"),
                listOf("中央", "少儿"),
                listOf("中央", "少儿"),
            )
        ),
        "CCTV-15音乐" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "15"),
                listOf("cctv", "音乐"),
                listOf("中央", "15"),
                listOf("中央", "十五"),
            )
        ),
        "CCTV-16奥匹" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "16"),
                listOf("cctv", "奥林匹克"),
                listOf("中央", "16"),
                listOf("中央", "十六"),
            )
        ),
        "CCTV-17农村" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "17"),
                listOf("cctv", "农村"),
                listOf("cctv", "农业"),
                listOf("中央", "17"),
                listOf("中央", "十七"),
            )
        ),
        "CCTV-1综合" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "1"),
                listOf("cctv", "综合"),
                listOf("中央", "1"),
                listOf("中央", "一"),
            )
        ),
        "CCTV-2财经" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "2"),
                listOf("cctv", "财经"),
                listOf("中央", "2"),
                listOf("中央", "二"),
            )
        ),
        "CCTV-3综艺" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "3"),
                listOf("cctv", "综艺"),
                listOf("中央", "3"),
                listOf("中央", "三"),
            )
        ),
        "CCTV-4国际" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "4"),
                listOf("cctv", "国际"),
                listOf("中央", "4"),
                listOf("中央", "四"),
            )
        ),
        "CCTV-5体育" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "5"),
                listOf("cctv", "体育"),
                listOf("中央", "5"),
                listOf("中央", "五"),
            )
        ),
        "CCTV-6电影" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "6"),
                listOf("cctv", "电影"),
                listOf("中央", "6"),
                listOf("中央", "六"),
            )
        ),
        "CCTV-7军事" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "7"),
                listOf("cctv", "军事"),
                listOf("cctv", "国防"),
                listOf("cctv", "军农"),
                listOf("中央", "7"),
                listOf("中央", "七"),
            )
        ),
        "CCTV-8电视" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "8"),
                listOf("cctv", "电视"),
                listOf("中央", "8"),
                listOf("中央", "八"),
            )
        ),
        "CCTV-9纪录" to standardCctvChannelNameTest(
            listOf(
                listOf("cctv", "9"),
                listOf("cctv", "纪录"),
                listOf("中央", "9"),
                listOf("中央", "九"),
            )
        ),
        "上海卫视" to { name: String ->
            name.contains("上海卫视")
                    || name.contains("东方卫视")
                    || name.contains("上海台")
                    || name.contains("上海东方卫视")
        },
        "福建卫视" to { name: String ->
            name.contains("福建卫视")
                    || name.contains("福建东南卫视")
                    || name.contains("东南卫视")
        },
    )

    // 1. 将原先的 hybridWebViewUrl 改名为 defaultHybridWebViewUrl 作为兜底默认值
    private val defaultHybridWebViewUrl = mapOf(
        "CCTV-1综合" to listOf(
            "https://tv.cctv.com/live/cctv1/",
            "https://yangshipin.cn/tv/home?pid=600001859",
            "https://v.lib.tju.edu.cn/tv-show-detail/3",
            "https://app.hfbtc.cn/shows/2/6.html",
            "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=11200132825562653886"
        ),
        "CCTV-2财经" to listOf(
            "https://tv.cctv.com/live/cctv2/",
            "https://yangshipin.cn/tv/home?pid=600001800",
        ),
        "CCTV-3综艺" to listOf(
            "https://tv.cctv.com/live/cctv3/",
            "http://m.miguvideo.com/m/liveDetail/624878271?channelId=10010001005"
        ),
        "CCTV-4国际" to listOf(
            "https://tv.cctv.com/live/cctv4/",
            "https://yangshipin.cn/tv/home?pid=600001814",
        ),
        "CCTV-5体育" to listOf(
            "https://tv.cctv.com/live/cctv5/",
            "https://yangshipin.cn/tv/home?pid=600001818",
        ),
        "CCTV-5+赛事" to listOf(
            "https://tv.cctv.com/live/cctv5plus/",
            "https://yangshipin.cn/tv/home?pid=600001817",
        ),
        "CCTV-6电影" to listOf(
            "https://tv.cctv.com/live/cctv6/",
        ),
        "CCTV-7军事" to listOf(
            "https://tv.cctv.com/live/cctv7/",
            "https://yangshipin.cn/tv/home?pid=600004092",
        ),
        "CCTV-8电视" to listOf(
            "https://tv.cctv.com/live/cctv8/",
        ),
        "CCTV-9纪录" to listOf(
            "https://tv.cctv.com/live/cctvjilu/",
            "https://yangshipin.cn/tv/home?pid=600004078",
        ),
        "CCTV-10科教" to listOf(
            "https://tv.cctv.com/live/cctv10/",
            "https://yangshipin.cn/tv/home?pid=600001805",
        ),
        "CCTV-11戏曲" to listOf(
            "https://tv.cctv.com/live/cctv11/",
            "https://yangshipin.cn/tv/home?pid=600001806",
        ),
        "CCTV-12社法" to listOf(
            "https://tv.cctv.com/live/cctv12/",
            "https://yangshipin.cn/tv/home?pid=600001807",
        ),
        "CCTV-13新闻" to listOf(
            "https://tv.cctv.com/live/cctv13/",
            "https://yangshipin.cn/tv/home?pid=600001811",
            "https://v.douyin.com/BYo9353pcyI/"
        ),
        "CCTV-14少儿" to listOf(
            "https://tv.cctv.com/live/cctvchild/",
            "https://yangshipin.cn/tv/home?pid=600001809",
        ),
        "CCTV-15音乐" to listOf(
            "https://tv.cctv.com/live/cctv15/",
            "https://yangshipin.cn/tv/home?pid=600001815",
        ),
        "CCTV-16奥匹" to listOf(
            "https://tv.cctv.com/live/cctv16/",
            "https://yangshipin.cn/tv/home?pid=600098637",
        ),
        "CCTV-17农村" to listOf(
            "https://tv.cctv.com/live/cctv17/",
        ),
        "北京卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002309",
        ),
        "江苏卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002521",
        ),
        "上海卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002483",
        ),
        "浙江卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002520",
        ),
        "湖南卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002475",
        ),
        "湖北卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002508",
        ),
        "广东卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002485",
        ),
        "广西卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002509",
        ),
        "黑龙江卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002498",
        ),
        "海南卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002506",
        ),
        "重庆卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002531",
        ),
        "深圳卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002481",
        ),
        "四川卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002516",
        ),
        "河南卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002525",
        ),
        "福建卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002484",
        ),
        "贵州卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002490",
        ),
        "江西卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002503",
        ),
        "辽宁卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002505",
        ),
        "安徽卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002532",
        ),
        "河北卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002493",
        ),
        "山东卫视" to listOf(
            "https://yangshipin.cn/tv/home?pid=600002513",
        ),
        "福建新闻" to listOf(
            "https://live.fjtv.net/xwpd/"
        ),
        "福建旅游" to listOf(
            "https://live.fjtv.net/dspd/"
        )
    )

    // 2. 声明一个用于存储从远端加载的自定义配置的变量
    private var customHybridWebViewUrl: Map<String, List<String>>? = null

    /**
     * 3. 提供一个异步方法，从指定的 URL 加载自定义的 WebView 解析规则
     *
     * 期望远端 URL 返回的文本格式示例：
     * CCTV-1综合,webview://https://custom.url.com/1
     * CCTV-1综合,webview://https://custom.url.com/2
     * 湖南卫视,webview://https://custom.url.com/hunan
     */
    suspend fun loadHybridWebViewUrlFromRemote(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val newMap = mutableMapOf<String, MutableList<String>>()

                    // 遍历文本的每一行，解析成 Map<String, List<String>>
                    responseText.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            val parts = line.split(",", limit = 2)
                            if (parts.size == 2) {
                                val channelName = parts[0].trim()
                                var webUrl = parts[1].trim()

                                // 移除 "webview://" 前缀（如果存在）
                                val webviewPrefix = "webview://"
                                if (webUrl.startsWith(webviewPrefix)) {
                                    webUrl = webUrl.substring(webviewPrefix.length)
                                }

                                newMap.getOrPut(channelName) { mutableListOf() }.add(webUrl)
                            }
                        }
                    }

                    // 只有成功解析且不出错时，才覆盖自定义配置
                    if (newMap.isNotEmpty()) {
                        customHybridWebViewUrl = newMap
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 网络异常或解析失败时，不做任何处理。
                // customHybridWebViewUrl 依然为空，系统会自动降级使用 default 配置。
            }
        }
    }


    private fun standardChannelName(name: String): String {
        return standardChannelNameTest.entries.firstOrNull { it.value.invoke(name) }?.key
            ?: name
    }

    const val HYBRID_WEB_VIEW_URL_PREFIX = "webview://"

    // 4. 优化获取逻辑：优先从 custom 字典中获取，若无则回退到 default 字典中查找
    fun getHybridWebViewUrl(channelName: String): List<String>? {
        val name = standardChannelName(channelName)

        // 优先尝试获取自定义配置，如果没有自定义配置或配置中不包含该频道，再回退使用默认配置
        val urls = customHybridWebViewUrl?.get(name) ?: defaultHybridWebViewUrl[name]

        return urls?.map { "${HYBRID_WEB_VIEW_URL_PREFIX}${it}" }
    }

    fun isHybridWebViewUrl(url: String): Boolean {
        return url.startsWith(HYBRID_WEB_VIEW_URL_PREFIX)
    }

    fun getHybridWebViewUrlProvider(url: String): String {
        return if (url.contains("https://tv.cctv.com")) "央视网"
        else if (url.contains("https://yangshipin.cn")) "央视频"
        else if (url.contains("https://v.douyin.com")) "抖音"
        else if (url.contains("http://m.miguvideo.com")) "咪咕视频"
        else "未知"
    }

    fun urlSupportPlayback(url: String): Boolean {
        return listOf("pltv", "PLTV", "tvod", "TVOD").any { url.contains(it) }
    }

    fun urlToCanPlayback(url: String): String {
        return url
            .replace("PLTV", "tvod")
            .replace("pltv", "tvod")
    }
}