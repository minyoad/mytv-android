package top.yogiczy.mytv.tv.ui.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.network.await
import top.yogiczy.mytv.core.data.utils.Logger

object Provinces {
    private val log = Logger.create("Provinces")

    /** 全国省级行政区（不含省/市/自治区等后缀） */
    val ALL: List<String> = listOf(
        "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
        "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
        "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
        "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆",
//        "台湾", "香港", "澳门",
    )

    /** 判断分组名是否为省份分组（分组名通常为“省份”或“省份+频道”） */
    fun isProvinceGroup(groupName: String): Boolean = ALL.any { groupName.contains(it) }

    /** 根据当前IP解析省份 */
    suspend fun resolveCurrentProvince(): String? = withContext(Dispatchers.IO) {
        try {
            val response = OkHttp.client.newCall(
                Request.Builder()
                    .url("https://ip.mybacc.com/query")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).await()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            normalizeProvince(json.optString("province"))
        } catch (ex: Exception) {
            log.e("IP归属地解析失败", ex)
            null
        }
    }

    /** 规范化省份名称，如“广东省”->“广东”、“广西壮族自治区”->“广西” */
    fun normalizeProvince(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var province = raw.trim()
        listOf(
            "维吾尔自治区", "壮族自治区", "回族自治区", "自治区",
            "特别行政区", "省", "市",
        ).forEach { province = province.removeSuffix(it) }
        return province.takeIf { it in ALL }
    }
}
