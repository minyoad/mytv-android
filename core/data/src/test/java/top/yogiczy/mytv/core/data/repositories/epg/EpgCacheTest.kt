package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.repositories.ValidatedFileCache
import java.io.File

class EpgCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val validate: suspend (File) -> EpgList = { it.inputStream().use { input -> EpgXmlParser.parse(input) } }

    @Test fun truncatedXmlDoesNotReplaceValidScheduleOrEtag() = runBlocking<Unit> {
        val file = File(temporary.root, "epg.xml")
        val cache = ValidatedFileCache(file)
        val original = "<tv><channel id=\"a\"><display-name>A</display-name></channel></tv>"
        cache.load({ true }, validate) { stage, _ ->
            stage.writeText(original)
            ValidatedFileCache.Download.Modified("good")
        }
        val result = cache.load({ true }, validate) { stage, _ ->
            stage.writeText("<tv><channel id=\"a\"><display-name>Incomplete")
            ValidatedFileCache.Download.Modified("broken")
        }
        assertNotNull(result.refreshError)
        assertEquals("A", result.value.single().channel)
        assertEquals(original, file.readText())
        assertEquals("good", File(file.path + ".etag").readText())
    }

    @Test fun validEmptyScheduleIsNotReportedAsFailure() = runBlocking<Unit> {
        val file = File(temporary.root, "epg.xml")
        val result = ValidatedFileCache(file).load({ true }, validate) { stage, _ ->
            stage.writeText("<tv/>")
            ValidatedFileCache.Download.Modified("empty")
        }
        assertNull(result.refreshError)
        assertTrue(result.value.isEmpty())
        assertEquals("empty", File(file.path + ".etag").readText())
    }
}
