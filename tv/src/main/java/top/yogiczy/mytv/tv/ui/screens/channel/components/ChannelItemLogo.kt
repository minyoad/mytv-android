package top.yogiczy.mytv.tv.ui.screens.channel.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil.compose.AsyncImage

@Composable
fun ChannelItemLogo(
    modifier: Modifier = Modifier,
    logoProvider: () -> String?,
) {
    AsyncImage(
        modifier = modifier,
        model = logoProvider(),
        contentDescription = null,
    )
}
