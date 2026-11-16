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
import java.text.SimpleDateFormat
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
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { isLenient = false }
        fun parseDate(value: String?): Long? = try { value?.let { dateFormat.parse(it)?.time } } catch (_: Exception) { null }

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
}
