package top.yogiczy.mytv.tv.ui.screens.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import top.yogiczy.mytv.tv.ui.screens.components.Qrcode
import top.yogiczy.mytv.tv.utlis.HttpServer

@Composable
fun SettingsCategoryPush(
    modifier: Modifier = Modifier,
) {
    val serverUrl: String = HttpServer.serverUrl

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Qrcode(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .width(200.dp)
                    .height(200.dp),
                textProvider = { serverUrl },
            )

            Text("局域网设置地址：${serverUrl}")
            Text("请使用同一局域网内的手机或电脑扫码访问")
            Text("打开后可添加 M3U/TXT 直播源和 XML/XML.GZ 节目单")
        }
    }
}