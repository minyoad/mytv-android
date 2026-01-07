package top.yogiczy.mytv.tv.ui.screens.channel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

@Composable
fun ChannelItemLogo(
    modifier: Modifier = Modifier,
    logoProvider: () -> String?,
    textFallbackProvider: () -> String? = { null },
) {
    val logo = logoProvider()
    val textFallback = textFallbackProvider()
    var isError by remember(logo) { mutableStateOf(false) }

    if (logo.isNullOrBlank() || isError) {
        if (!textFallback.isNullOrBlank()) {
            ChannelItemLogoFallback(
                modifier = modifier,
                text = textFallback,
            )
        }
    } else {
        AsyncImage(
            modifier = modifier,
            model = ImageRequest.Builder(LocalContext.current)
                .data(logo)
                .crossfade(true)
                .build(),
            contentDescription = null,
            onError = { isError = true }
        )
    }
}

@Composable
private fun ChannelItemLogoFallback(
    modifier: Modifier = Modifier,
    text: String,
) {
    val cleanText = remember(text) {
        // 移除所有非字母和非汉字字符（包括数字、标点符号、特殊符号如【】等）
        text.replace(Regex("[^\\p{L}]"), "")
            .ifBlank { text } // 如果全部被移除（例如纯数字），则回退到原始文本
            .take(1)
            .uppercase()
    }

    // 根据原始文本生成固定的背景色
    val backgroundColor = remember(text) {
        val colors = listOf(
            Color(0xFFF44336), Color(0xFFE91E63), Color(0xFF9C27B0),
            Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF2196F3),
            Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688),
            Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39),
            Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722),
            Color(0xFF795548), Color(0xFF607D8B)
        )
        colors[abs(text.hashCode()) % colors.size]
    }

    // 外层 Box 占用完整的布局空间（由 modifier 定义）
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 内层 Box 负责绘制圆形背景，并缩小至 60%
        Box(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .aspectRatio(1f) // 保持正方形比例
                .background(
                    color = backgroundColor,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cleanText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    color = Color.White
                )
            )
        }
    }
}
