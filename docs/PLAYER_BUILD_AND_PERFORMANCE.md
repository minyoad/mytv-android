# 播放器依赖与性能验证

## 已提交的二进制

`player-artifacts.sha256` 固定仓库中全部 Media3 AAR 和 IJK so 的 SHA-256。
`verifyPlayerArtifacts` 在 TV/IJK 的 `preBuild` 前检查文件集合与内容，新增、缺失、修改文件都会失败。
更新二进制时应先核对来源、许可证、ABI 和构建报告，再审查更新清单。

`player-sources.json` 区分已知事实和待核实记录。Media3 common 的内嵌版本为 1.10.1；
候选源码固定为 `minyoad/media@37c0c6168694423b6177ccd6e16980d0d6f20549`，其 constants.gradle 也声明 1.10.1。
相同版本号不能证明现有 AAR 来自该提交，因此历史 source revision 与 rebuild_verified 保持未知/false。

## Java AAR 重建

准备 JDK、Android SDK，并按 fork 的 Gradle/AGP 要求配置网络代理与 CA。使用干净检出：

```sh
git clone https://github.com/minyoad/media.git /path/to/media
git -C /path/to/media checkout 37c0c6168694423b6177ccd6e16980d0d6f20549
python tools/rebuild_media3.py --source /path/to/media --output /path/to/rebuilt-aar
```

脚本验证提交和工作区，只编译应用需要的十个 Java AAR，输出到独立目录，逐项比较 AAR 内容并记录差异。
不要使用 fork 的 assembleLibs 任务：其末尾调用固定 Windows 路径的 move.bat。
脚本不替换 `tv/libs`，也不宣称候选源码与历史二进制一致。

FFmpeg decoder AAR 和 IJK so 缺少准确的源码提交、NDK/FFmpeg 版本、补丁及 configure 参数。
在补齐这些信息之前，无法诚实地提供完整的历史 native 二进制源码重建；哈希锁定不等同于源码可复现。
参考 fork 的 `libraries/decoder_ffmpeg/README.md`，将实际参数及第三方许可证随下一次重建一起归档。

## 启动和播放首帧测量

日志 tag 为 `Performance`，仅包含指标名和单调时钟耗时，不记录直播 URL：

- `startup_to_composition_ms`：Application.onCreate 至第一次成功 Compose 组合；不是屏幕实际呈现时间。
- `startup_to_first_video_frame_ms`：Application.onCreate 至第一帧视频回调。
- `playback_to_first_frame_ms`：每次 prepare 至该播放器首帧；被停止、失败、换台中断或释放的尝试不计成功样本。

Media3 使用 onRenderedFirstFrame；IJK 使用 MEDIA_INFO_VIDEO_RENDERING_START。
不使用 ready 代替首帧回调。内核切换/Surface 重建会影响结果，应按相同操作分别采集。

```sh
adb logcat -c
adb logcat -v threadtime Performance:I '*:S' > performance.log
# 在电视上执行启动、换台、切换内核后停止采集
python tools/performance_report.py performance.log
```

冷启动每轮先执行 `adb shell am force-stop top.yogiczy.mytv.tv`，再启动应用。
分别测试有缓存/无缓存、IJK/Media3、SurfaceView/TextureView；每种条件至少 20 次。
固定设备、网络、频道、解码方式和构建类型；记录失败次数及超时，不能只报告成功样本的 P50/P95。
Debug 结果仅用于开发比较；发布性能应使用相同签名与优化设置的 release 包。

当前云端没有连接电视，仓库不包含伪造的基线数字；完成设备采样后再制定性能预算。
