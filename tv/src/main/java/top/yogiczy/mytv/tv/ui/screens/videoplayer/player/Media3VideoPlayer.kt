package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(UnstableApi::class)
class Media3VideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : VideoPlayer(coroutineScope) {
    private val log = Logger.create(Media3VideoPlayer::class.java.simpleName)

    private val videoPlayer by lazy {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (Configs.videoPlayerForceSoftDecode)
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply { playWhenReady = true }
    }
    private val dataSourceFactory by lazy {
        DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory().apply {
                setUserAgent(Configs.videoPlayerUserAgent)
                setConnectTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setReadTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setKeepPostFor302Redirects(true)
                setAllowCrossProtocolRedirects(true)
            },
        )
    }

    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()
    private var updatePositionJob: Job? = null

    /** IO 类错误（瞬态，如流中断）已重试次数；源不可达类错误不重试 */
    private var ioRetryCount = 0
    private var ioRetryJob: Job? = null

    /** 瞬态 IO 错误的最大重试次数，超过即上报错误交由上层切换下一个源 */
    private val maxIoRetryCount = 1

    /** 瞬态 IO 错误的重试间隔 */
    private val ioRetryIntervalMs = 1000L


    /**
     * 为 RTSP 连接建立过程加超时的 SocketFactory。
     *
     * media3 通过 SocketFactory.createSocket(host, port) 建立 RTSP 的 TCP 连接，
     * 而该重载的默认实现是 new Socket(host, port)，不带任何超时约束：
     * 目标主机丢包时会一直卡在 TCP 重传（可达数十秒），期间不会回调 onPlayerError，
     * 只能等"加载超时"看门狗兜底才换源。改用 Socket.connect(addr, timeout) 后，
     * 不可用源能在超时后立刻报错，从而尽快切换到下一个源。
     *
     * 域名解析与 TCP 连接各自独立计时（与 OkHttp 的 connectTimeout 语义一致），
     * 因此最坏情况下总耗时为 2 * connectTimeoutMs。
     */
    private class TimeoutSocketFactory(private val connectTimeoutMs: Int) : SocketFactory() {
        /**
         * 解析主机名。
         *
         * InetAddress.getByName 不提供超时参数，DNS 服务器不可达时会长时间阻塞
         * （不受 connectTimeoutMs 约束），导致不可用源一直挂到看门狗触发。
         * 这里放到守护线程执行并以 Future 限时，超时按 UnknownHostException 处理，
         * 从而能被 isSourceUnreachable() 识别并立即换源。
         */
        private fun resolve(host: String): InetAddress {
            val future = dnsExecutor.submit<InetAddress> { InetAddress.getByName(host) }

            return try {
                future.get(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (ex: TimeoutException) {
                // 放弃等待；无法真正中断阻塞在 DNS 上的线程，但它是守护线程，
                // 且系统解析器自身超时后线程会自然结束
                future.cancel(true)
                throw UnknownHostException("DNS解析超时: $host").apply { initCause(ex) }
            } catch (ex: ExecutionException) {
                val cause = ex.cause
                throw if (cause is UnknownHostException) cause
                else UnknownHostException("$host: ${cause?.message}").apply { initCause(cause) }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw UnknownHostException(host)
            }
        }

        private fun newConnectedSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress? = null,
            localPort: Int = 0,
        ): Socket = Socket().apply {
            // 仅在调用方明确指定本地端点时绑定，否则保持系统默认自动绑定
            if (localAddress != null || localPort != 0) {
                bind(InetSocketAddress(localAddress, localPort))
            }
            connect(InetSocketAddress(address, port), connectTimeoutMs)
        }

        override fun createSocket(host: String, port: Int): Socket =
            newConnectedSocket(resolve(host), port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = newConnectedSocket(resolve(host), port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            newConnectedSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = newConnectedSocket(address, port, localAddress, localPort)

        private companion object {
            /** 仅用于执行可能阻塞的 DNS 解析，守护线程避免影响进程退出 */
            private val dnsExecutor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "rtsp-dns-resolver").apply { isDaemon = true }
            }
        }
    }

    /** RTSP 连接超时：不低于 1s，且最多 5s，避免不可用源长时间占用换源流程 */
    private fun rtspSocketFactory(): SocketFactory =
        TimeoutSocketFactory(Configs.videoPlayerLoadTimeout.coerceIn(1_000L, 5_000L).toInt())


    private fun getMediaSource(uri: Uri, contentType: Int? = null): MediaSource? {
        val mediaItem = MediaItem.fromUri(uri)

        if (uri.toString().startsWith("rtp://")) {
            return RtspMediaSource.Factory()
                .setSocketFactory(rtspSocketFactory())
                .createMediaSource(mediaItem)
        }

        return when (val type = contentType ?: Util.inferContentType(uri)) {
            C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_RTSP -> {
                // RTSP 源默认尝试 UDP，收不到 RTP 数据需等 DEFAULT_TIMEOUT_MS=5s 才回退 TCP，
                // 公网/NAT 场景下会明显拖慢出图（对齐 IJK 的 rtsp_transport=tcp，直接走 TCP）
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setDebugLoggingEnabled(true)
                    .setSocketFactory(rtspSocketFactory())
                    .createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                triggerError(
                    PlaybackException.UNSUPPORTED_TYPE.copy(
                        errorCodeName = "${PlaybackException.UNSUPPORTED_TYPE.message}_$type"
                    )
                )
                null
            }
        }
    }

    /**
     * 判断是否为“源不可达”类错误（连接被拒/被中止、连接超时、主机不可达、域名解析失败）。
     * 这类错误产生于连接建立阶段，重试必然失败，应立即上报错误以切换下一个源。
     */
    private fun Throwable.isSourceUnreachable(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is ConnectException ||
                cause is NoRouteToHostException ||
                cause is PortUnreachableException ||
                cause is UnknownHostException ||
                cause is SocketTimeoutException
            ) {
                return true
            }
            if (cause.cause === cause) break
            cause = cause.cause
        }
        return false
    }

    private fun prepare(uri: Uri, contentType: Int? = null) {
        val mediaSource = getMediaSource(uri, contentType)

        if (mediaSource != null) {
            contentTypeAttempts[contentType ?: Util.inferContentType(uri)] = true
            ioRetryCount = 0
            videoPlayer.setMediaSource(mediaSource)
            videoPlayer.prepare()
            videoPlayer.play()
            triggerPrepared()
        }
        updatePositionJob?.cancel()
        updatePositionJob = null
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            triggerResolution(videoSize.width, videoSize.height)
        }


        override fun onPlayerError(ex: androidx.media3.common.PlaybackException) {
            log.e("onPlayerError", ex)

            when (ex.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> {
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
                }

                // 网络/数据源类错误（RTSP 等统一上报为 IO_UNSPECIFIED）：
                // 原先的立即无限重试会让播放器始终停留在缓冲态——既不换源也不提示。
                // 现在区分两类处理，避免坏源等待过久：
                //  - 源不可达（连不上）：重试必然失败，立即上报错误换源；
                //  - 其余瞬态错误（如流中断）：仅做一次快速重试，仍失败同样换源。
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    val unreachable = ex.isSourceUnreachable()
                    log.w("IO错误 源不可达=$unreachable 已重试=$ioRetryCount/$maxIoRetryCount")

                    if (!unreachable && ioRetryCount < maxIoRetryCount) {
                        ioRetryCount++
                        ioRetryJob?.cancel()
                        ioRetryJob = coroutineScope.launch {
                            delay(ioRetryIntervalMs)
                            videoPlayer.seekToDefaultPosition()
                            videoPlayer.prepare()
                        }
                    } else {
                        triggerError(PlaybackException(ex.errorCodeName, ex.errorCode))
                    }
                }

                // 当解析容器不支持时，尝试使用其他解析容器
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    videoPlayer.currentMediaItem?.localConfiguration?.uri?.let {
                        if (contentTypeAttempts[C.CONTENT_TYPE_HLS] != true) {
                            prepare(it, C.CONTENT_TYPE_HLS)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_RTSP] != true) {
                            prepare(it, C.CONTENT_TYPE_RTSP)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true) {
                            prepare(it, C.CONTENT_TYPE_OTHER)
                        } else {
                            val type = Util.inferContentType(it)
                            triggerError(
                                PlaybackException.UNSUPPORTED_TYPE.copy(
                                    errorCodeName = "${PlaybackException.UNSUPPORTED_TYPE.message}_$type"
                                )
                            )
                        }
                    }
                }

                else -> {
                    triggerError(PlaybackException(ex.errorCodeName, ex.errorCode))
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                triggerError(null)
                triggerBuffering(true)
            } else if (playbackState == Player.STATE_READY) {
                ioRetryCount = 0
                ioRetryJob?.cancel()
                ioRetryJob = null
                triggerReady()

                updatePositionJob?.cancel()
                updatePositionJob = coroutineScope.launch {

                    while (true) {
                        val livePosition =
                            System.currentTimeMillis() - videoPlayer.currentLiveOffset

                        triggerCurrentPosition(if (livePosition > 0) livePosition else videoPlayer.currentPosition)
                        delay(1000)
                    }
                }

                triggerDuration(videoPlayer.duration)
            }

            if (playbackState != Player.STATE_BUFFERING) {
                triggerBuffering(false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            triggerIsPlayingChanged(isPlaying)
        }
    }

    private val metadataListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(
                videoMimeType = format.sampleMimeType ?: "",
                videoWidth = format.width,
                videoHeight = format.height,
                videoColor = format.colorInfo?.toLogString() ?: "",
                // TODO 帧率、比特率目前是从tag中获取，有的返回空，后续需要实时计算
                videoFrameRate = format.frameRate,
                videoBitrate = format.bitrate,
            )
            triggerMetadata(metadata)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(videoDecoder = decoderName)
            triggerMetadata(metadata)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(
                audioMimeType = format.sampleMimeType ?: "",
                audioChannels = format.channelCount,
                audioSampleRate = format.sampleRate,
            )
            triggerMetadata(metadata)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(audioDecoder = decoderName)
            triggerMetadata(metadata)
        }
    }

    private val eventLogger = EventLogger()

    override fun initialize() {
        super.initialize()
        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        videoPlayer.addAnalyticsListener(eventLogger)
    }

    override fun release() {
        ioRetryJob?.cancel()
        ioRetryJob = null
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.release()
        super.release()
    }

    override fun prepare(url: String) {
        contentTypeAttempts.clear()
        prepare(Uri.parse(url.let {
            if (url.endsWith("?")) "${it}t" else it
        }))
    }

    override fun play() {
        videoPlayer.play()
    }

    override fun pause() {
        videoPlayer.pause()
    }

    override fun seekTo(position: Long) {
        videoPlayer.seekTo(position)
    }

    override fun stop() {
        ioRetryJob?.cancel()
        ioRetryJob = null
        videoPlayer.stop()
        updatePositionJob?.cancel()
        super.stop()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        videoPlayer.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView) {
//        TODO("Not yet implemented")
        videoPlayer.setVideoTextureView(textureView)
    }
}