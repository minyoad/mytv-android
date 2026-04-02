package top.yogiczy.mytv.tv.ui.screens.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.tv.ui.material.Visible
import top.yogiczy.mytv.tv.ui.screens.webview.components.WebViewPlaceholder
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    modifier: Modifier = Modifier,
    urlProvider: () -> String = { "${ChannelUtil.HYBRID_WEB_VIEW_URL_PREFIX}https://tv.cctv.com/live/index.shtml" },
    onVideoResolutionChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val url = urlProvider().replace(ChannelUtil.HYBRID_WEB_VIEW_URL_PREFIX, "")
    // 使用 isLoading 状态来控制加载动画和 WebView 的可见性
    var isLoading by remember { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .background(Color.Black)
                .alpha(if (isLoading) 0f else 1f),
            factory = {
                MyWebView(it).apply {
                    webViewClient = MyClient(
                        onPageStarted = { isLoading = true },
                    )

                    setBackgroundColor(Color.Black.toArgb())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT

                        // 提速优化 1：禁止自动加载图片和拦截网络图片
                        loadsImagesAutomatically = false
                        blockNetworkImage = true

                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportZoom(false)
                        displayZoomControls = false
                        builtInZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                    }

                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false

                    addJavascriptInterface(
                        MyWebViewInterface(
                            onVideoResolutionChanged = { width, height ->
                                isLoading = false
                                onVideoResolutionChanged(width, height)
                            },
                        ), "Android"
                    )
                }
            },
            update = { it.loadUrl(url) },
        )

        Visible({ isLoading }) { WebViewPlaceholder() }
    }
}

class MyClient(
    private val onPageStarted: () -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onPageStarted()
        super.onPageStarted(view, url, favicon)
    }

    // 提速优化 2：拦截无用资源的请求，极大缩短网页加载时间
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val reqUrl = request?.url?.toString() ?: ""
        val blockKeywords = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", // 屏蔽图片
            "google-analytics", "hm.baidu.com", "umeng.com",  // 屏蔽常见的统计脚本
            "adsystem", "doubleclick"                         // 屏蔽常见广告
        )

        if (blockKeywords.any { reqUrl.contains(it, ignoreCase = true) }) {
            // 返回一个空的响应，直接掐断该网络请求
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(
            """
            ;(async () => {
                // ==========================================
                // 1. 全局注入样式：暴力霸屏，遮挡原生网页
                // ==========================================
                const style = document.createElement('style');
                style.innerHTML = `
                    /* 隐藏整个网页自带的滚动条 */
                    html, body { width: 100vw !important; height: 100vh !important; background: #000 !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; }
                    
                    /* 终极 video 霸屏 CSS */
                    video {
                        width: 100vw !important; height: 100vh !important;
                        position: fixed !important; top: 0 !important; left: 0 !important; bottom: 0 !important; right: 0 !important;
                        z-index: 2147483647 !important; background: #000 !important;
                        max-width: none !important; max-height: none !important;
                        transform: none !important; object-fit: contain !important;
                        border: none !important; margin: 0 !important; padding: 0 !important;
                        display: block !important; visibility: visible !important; opacity: 1 !important;
                    }
                    
                    /* 遮罩层，阻挡原生网页的点击暂停事件 */
                    #ytv-mask {
                        width: 100vw !important; height: 100vh !important;
                        position: fixed !important; top: 0 !important; left: 0 !important;
                        z-index: 2147483648 !important; background: transparent !important;
                    }
                `;
                document.head.appendChild(style);

                // 2. 创建防误触遮罩
                if(!document.getElementById('ytv-mask')) {
                    const mask = document.createElement('div');
                    mask.id = 'ytv-mask';
                    mask.addEventListener('click', (e) => { e.stopPropagation(); e.preventDefault(); });
                    document.body.appendChild(mask);
                }

                let isReady = false;

                // ==========================================
                // 3. 动态维护定时器：击碎 CCTV 等网页的层叠上下文陷阱
                // ==========================================
                setInterval(() => {
                    const videoEl = document.querySelector('video');
                    if (!videoEl) return;

                    // 持续剥除父元素的各种限制，特别是 z-index 和 position 陷阱
                    let p = videoEl.parentElement;
                    while(p && p !== document.body && p !== document.documentElement) {
                        const style = window.getComputedStyle(p);
                        
                        // 移除会导致 position: fixed 降级或被裁切的属性
                        if (style.transform !== 'none') p.style.setProperty('transform', 'none', 'important');
                        if (style.filter !== 'none') p.style.setProperty('filter', 'none', 'important');
                        if (style.contain !== 'none') p.style.setProperty('contain', 'none', 'important');
                        if (style.clipPath !== 'none') p.style.setProperty('clip-path', 'none', 'important');
                        
                        // 致命一击：移除父级的透明度、层级和定位，防止 video 被困在低 z-index 的图层中 (解决CCTV无法全屏的核心)
                        if (style.opacity !== '1') p.style.setProperty('opacity', '1', 'important');
                        if (style.zIndex !== 'auto') p.style.setProperty('z-index', 'auto', 'important');
                        if (style.position !== 'static') p.style.setProperty('position', 'static', 'important');
                        
                        p = p.parentElement;
                    }

                    // 安全触发播放：捕获 Promise 异常
                    if (videoEl.paused) {
                        const playPromise = videoEl.play();
                        if (playPromise !== undefined) {
                            playPromise.catch(e => { /* 忽略拦截导致的 AbortError */ });
                        }
                    }

                    // 探测首帧就绪状态，通知 Android 端去掉加载动画
                    if (!isReady && videoEl.videoWidth > 0 && videoEl.videoHeight > 0) {
                        isReady = true;
                        Android.changeVideoResolution(videoEl.videoWidth, videoEl.videoHeight);
                    }
                }, 300);

                // ==========================================
                // 4. 防死锁兜底：5秒后若仍未上报分辨率则强制解锁
                // ==========================================
                setTimeout(() => {
                    if (!isReady) {
                        isReady = true;
                        Android.changeVideoResolution(1920, 1080);
                    }
                }, 5000);
            })();
            """.trimIndent()
        ) {}
    }
}

class MyWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }
}

class MyWebViewInterface(
    private val onVideoResolutionChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun changeVideoResolution(width: Int, height: Int) {
        mainHandler.post {
            onVideoResolutionChanged(width, height)
        }
    }
}