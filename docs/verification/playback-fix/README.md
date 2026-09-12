# 0.1.1-dev 播放修复验证

日期：2026-09-12。反馈：首页《海洋奇缘：启航》和从已有收藏打开《权力的游戏第八季》无法播放。

## 网页对照

在 Chrome 中打开相同站点、影片和默认在线线路，均观察到实际画面、持续前进的时间和 readyState=4：

| 影片 | 网页路径 | 在线线路 | 网页结果 |
| --- | --- | --- | --- |
| 海洋奇缘：启航 | `/play/5813774-4-1.html` | BD1 / 4kvm | HLS，video.src 为 blob，时长 6913 秒；播放至 105 秒后暂停 |
| 权力的游戏第八季，第 01 集 | `/play/5811975-2-1.html` | HD3 / lbyy | MP4，时长 3188 秒；播放至 66 秒后暂停 |

电视 SQLite 中原有收藏的 routeId=5811975，与网页相同。收藏入口和首页共用 PlaybackController.open；此次故障来自线路解析，未发现收藏编号损坏。

## 原因与修复

- 原型的 encrypt=3 白名单仅有 BBA / rrmj / NBY，默认 BD1 / 4kvm 和 HD3 / lbyy 在调用网页解析前就被拒绝。核对公开 provider 脚本后，接入 4kvm、lbyy，以及使用同一入口的 bhyy。
- 原型仅接受 video.currentSrc 的 HTTPS 地址，HLS.js 的 blob 地址无法移交原生播放器。现在确认 video 所属实例为 HLS，并读取该次解析实际发出的清单请求；站点的 `/video_m3u8/secure.php` 虽无 `.m3u8` 后缀，仍明确设置 Media3 的 HLS 类型。使用网页请求实际采用的 Referer（包括不发送），不传递解析占位值或 blob。
- 仍要求 video 已取得有效时长与元数据；拒绝 blob 本身、非 HTTPS、带账号的 URL、未知 MSE 格式。网页移交原生播放器前销毁，地址只在内存中使用，不写入历史或验证文件。
- HD3 的第八季样本为 AVC High 10（`avc1.6E0032`），模拟器原生解码器报告超出能力。禁止选取超出解码能力的视频轨道；在轨道能力不足或首帧前播放报错时，按集名语义尝试尚未尝试的同集线路，保留请求的进度与暂停状态；新源出首帧后才保存。第八季实测 HD3 → BD1。不会循环尝试同一线路。
- 数据库结构未变；更新采用覆盖安装，保留已有收藏与历史。

依据：[Artplayer option 与 template](https://artplayer.org/document/en/advanced/built-in)、站点公开 provider 脚本及 [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls)。

## 验证状态

构建、17 项 Kotlin 单元测试与 lintDebug 通过。新增测试覆盖 HLS 带参数/无扩展名清单、MP4、拒绝 blob 与未就绪/不安全地址。

已独立核对 Chromecast 安装包 SHA256 与本机最终 APK 一致；原有第八季收藏和两条观看记录保留。ReportedResolverTest 在 Chromecast 通过（30.421 秒），验证 BD1 / HLS 和 HD3 / MP4 解析；这不等同于前台出画验收。

原有 HD7 / HD2 首帧、跳转、播放中/暂停中换源，以及 ReportedResolverTest 的三项模拟器回归全部通过（74.811 秒）。

TV 模拟器（API 34，1920×1080）已通过两个实际页面入口用例，耗时 48.578 秒：

- 首页 →《海洋奇缘：启航》默认 BD1：原生视频首帧、115:13 时长、45 秒跳转后持续播放。
- 已有测试收藏 →《权力的游戏第八季》：HD3 格式不受支持后自动选择 BD1 的同一集，原生首帧、53:07 时长、跳转后持续播放，返回收藏再打开续播，收藏内容不变。
- 独立 ReportedResolverTest 已在模拟器通过，验证 BD1 / HLS 与 HD3 / MP4 的解析交接。

真机入口回归尚待完成：配对 Chromecast（Android 14 / API 34）先处于系统屏保，随后休眠，无法取得前台画面；未将这次环境失败或模拟器通过计作 Chromecast 出画通过。

新增 ReportedPlaybackTest 通过实际 Compose 页面入口打开影片，检查 Media3 首帧、播放进度、兼容线路选择、45 秒跳转、已有收藏再次打开和本地续播；真机运行不修改收藏。只在显式 liveLibvio=true 时运行；收藏用例要求设备已收藏本片。模拟器可显式传 `-e seedEmulatorFavorite true` 创建本片测试收藏；该选项拒绝在非模拟器上使用。测试等待原生首帧前会推进 Compose 测试时钟，确保页面导航与视频 Surface 已挂载。

```sh
source scripts/android-env.sh
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$TV_SERIAL" shell am instrument -w -r -e liveLibvio true \
  -e class com.libvio.tv.ReportedPlaybackTest \
  com.libvio.tv.test/androidx.test.runner.AndroidJUnitRunner
```

原生画面：[海洋奇缘 BD1](screenshots/moana-bd1-native.png)、[第八季兼容线路](screenshots/game-of-thrones-compatible-native.png)。截图来自模拟器。

请先唤醒电视并退出屏保。本轮不代表全站 provider、所有集数、全片连续播放、断网恢复或 API 26–29 已验收。原型阶段的模拟器记录仍保留在 ../prototype/README.md。
