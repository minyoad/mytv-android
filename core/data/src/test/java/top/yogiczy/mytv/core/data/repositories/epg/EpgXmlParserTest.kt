package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpgXmlParserTest {
    private suspend fun parse(xml: String) = xml.byteInputStream().use { EpgXmlParser.parse(it) }

    @Test fun emptyScheduleIsValidSuccess() = runBlocking<Unit> {
        assertTrue(parse("<tv/>").isEmpty())
    }

    @Test fun malformedOrTruncatedXmlIsFailure() = runBlocking<Unit> {
        for (xml in listOf("<html>error</html>", "<tv><channel id=\"a\"><display-name>A", "<tv><programme")) {
            try {
                parse(xml)
                fail("must reject $xml")
            } catch (_: Exception) { }
        }
    }

    @Test fun invalidDatesAreSkippedAndProgramsAreSorted() = runBlocking<Unit> {
        val result = parse("""
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme channel="a" start="20261116080000 +0800" stop="20261116090000 +0800"><title>Later</title></programme>
              <programme channel="a" start="20261116070000 +0800" stop="20261116080000 +0800"><title>Earlier</title></programme>
              <programme channel="a" start="bad" stop="bad"><title>Invalid</title></programme>
              <programme channel="a" start="20261116090000 +0800" stop="20261116080000 +0800"><title>Reversed</title></programme>
            </tv>
        """.trimIndent())
        assertEquals(listOf("Earlier", "Later"), result.single().programmeList.map { it.title })
        assertEquals(3600000L, result.single().programmeList.first().endAt - result.single().programmeList.first().startAt)
    }
}
