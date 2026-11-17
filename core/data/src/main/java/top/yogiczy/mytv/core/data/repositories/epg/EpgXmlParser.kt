package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal object EpgXmlParser {
    suspend fun parse(
        input: InputStream,
        filteredChannels: Set<String> = emptySet(),
        normalize: (String) -> String = { it },
    ): EpgList {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "tv") {
            throw IOException("节目单根节点必须是tv")
        }
        val names = mutableMapOf<String, String>()
        val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()
        val filter = filteredChannels.map { it.lowercase(Locale.ROOT) }.toSet()
        fun parseDate(value: String?): Long? = parseXmltvDate(value)

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "channel" -> {
                    val id = parser.getAttributeValue(null, "id")
                    val name = normalize(readChildText(parser, "display-name"))
                    if (id != null && name.isNotEmpty() && (filter.isEmpty() || name.lowercase(Locale.ROOT) in filter)) {
                        names[id] = name
                    }
                }
                "programme" -> {
                    val id = parser.getAttributeValue(null, "channel")
                    if (id == null || id !in names) {
                        readChildText(parser, "")
                        continue
                    }
                    val start = parseDate(parser.getAttributeValue(null, "start"))
                    val end = parseDate(parser.getAttributeValue(null, "stop"))
                    val title = readChildText(parser, "title")
                    if (start != null && end != null && end > start && title.isNotBlank()) {
                        programmes.getOrPut(id) { mutableListOf() }.add(EpgProgramme(startAt = start, endAt = end, title = title))
                    }
                }
                else -> readChildText(parser, "")
            }
        }
        return EpgList(names.map { (id, name) ->
            var lastEnd = Long.MIN_VALUE
            val sorted = programmes[id].orEmpty().sortedBy { it.startAt }.filter {
                if (it.startAt < lastEnd) false else { lastEnd = it.endAt; true }
            }
            Epg(name, sorted)
        })
    }

    private suspend fun readChildText(parser: XmlPullParser, tag: String): String {
        val depth = parser.depth
        var text = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> throw IOException("节目单XML被截断")
                XmlPullParser.END_TAG -> if (parser.depth == depth) return text
                XmlPullParser.START_TAG -> if (parser.depth == depth + 1 && parser.name == tag) text = parser.nextText().trim()
            }
        }
    }

    /**
     * 手工解析 xmltv 日期（格式 yyyyMMddHHmmss ±HHmm），替代 SimpleDateFormat。
     * 大节目单（数十 MB、数十万条节目）解析时 SDF 的日历运算开销极大，
     * 在低端电视上可占解析总耗时的相当比例。
     *
     * 语义与原 SimpleDateFormat("yyyyMMddHHmmss Z", lenient=false) 对齐：
     * - 必须有 14 位数字日期，随后是空白与 ±HHmm 数字时区，否则返回 null；
     * - 月份/日/时/分/秒超出常规范围返回 null。
     */
    private fun parseXmltvDate(value: String?): Long? {
        if (value == null) return null
        val len = value.length
        var i = 0
        while (i < len && value[i] == ' ') i++
        if (len - i < 14) return null

        fun digit(): Int {
            val c = value[i]
            if (c < '0' || c > '9') throw IllegalArgumentException()
            i++
            return c - '0'
        }

        fun number(count: Int): Int {
            var v = 0
            repeat(count) {
                val d = digit()
                if (d < 0) throw IllegalArgumentException()
                v = v * 10 + d
            }
            return v
        }

        return try {
            val year = number(4)
            val month = number(2)
            val day = number(2)
            val hour = number(2)
            val minute = number(2)
            val second = number(2)
            if (month < 1 || month > 12 || day < 1 || day > 31 ||
                hour > 23 || minute > 59 || second > 59
            ) return null
            while (i < len && value[i] == ' ') i++
            if (i >= len) return null
            val sign = when (value[i]) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            i++
            val zoneHour = number(2)
            val zoneMinute = number(2)
            val offsetMinutes = sign * (zoneHour * 60 + zoneMinute)
            daysFromCivil(year, month, day) * 86_400_000L +
                (hour * 3600 + minute * 60 + second) * 1000L -
                offsetMinutes * 60_000L
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 自纪元起算的天数（公历格里历，Howard Hinnant 算法），无对象分配。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
