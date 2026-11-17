package top.yogiczy.mytv.tv.ui.screens.epg

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.tv.ui.theme.MyTVTheme

@Composable
fun EpgProgrammeProgressScreen(
    modifier: Modifier = Modifier,
    currentEpgProgrammeProvider: () -> EpgProgramme? = { null },
    videoPlayerCurrentPositionProvider: () -> Long = { System.currentTimeMillis() },
) {
    val currentEpgProgramme = currentEpgProgrammeProvider() ?: return

    Box(modifier = modifier.fillMaxSize()) {
        val trackColor = MaterialTheme.colorScheme.surface.copy(0.8f)
        val progressColor = MaterialTheme.colorScheme.inverseSurface
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .height(3.dp)
                .fillMaxWidth()
                // 进度在绘制阶段读取：position 每秒变化只会触发这个小条重绘（draw 失效），
                // 而不是本组件（fillMaxSize）乃至上层作用域的每秒重组
                .drawBehind {
                    drawRect(trackColor)
                    val progress = (
                        (videoPlayerCurrentPositionProvider() - currentEpgProgramme.startAt)
                            .toFloat() / (currentEpgProgramme.endAt - currentEpgProgramme.startAt)
                        ).coerceIn(0f, 1f)
                    if (progress > 0f) {
                        drawRect(progressColor, size = Size(size.width * progress, size.height))
                    }
                },
        ) {}
    }
}

@Preview(device = "id:tv_720p")
@Composable
private fun EpgProgrammeProgressScreenPreview() {
    MyTVTheme {
        EpgProgrammeProgressScreen(
            currentEpgProgrammeProvider = { EpgProgramme.EXAMPLE },
        )
    }
}