# 0.1.0-dev 原型验证

日期：2026-09-12。设备为本机 Android TV arm64 模拟器 `emulator-5556`（API 34，1920×1080 输出），不是 Google TV 真机。初始调研提交 `88b7115` 已独立核对推送到 `abdvl/libvio-tv-app` 的 `main`。

构建成功；11 项 Kotlin 单元测试、11 项 Python 契约测试、4 项 Android 实站测试通过。APK 和 14 张截图的校验值见 [manifest.json](manifest.json)。

## 已观察的结果

- Kotlin 单元测试覆盖发布入口作用域、外链拒绝、卡片去重、验证页拒绝、首页分类边界、在线/下载源区分、内部 ID、集名语义匹配、电影标签、续播阈值与时间跳转。
- `LivePlaybackTest`：样本 `/detail/5813548.html`，HD7 / BBA → HD2 / rrmj；Media3 实际首帧、43:53 时长、暂停/恢复、+30 秒跳转、换源保留位置、SQLite 源名/位置/internalId=3548 保存。
- `LiveCatalogTest`：五类首页互不串区、五类筛选、年份＋人气的真实链接、下一页、中文“流浪地球”搜索、详情与海报、`.lat` / `.cam` 选站读取。
- `TvJourneyTest`：默认首页焦点与向下进入内容；分类、目录、年份浮层、搜索、历史、收藏、设置页面。页面切换主要通过 Compose 语义点击，不等同于完整物理遥控器遍历。
- `TvPlayerJourneyTest`：HD7 暂停后切 HD2 保持暂停；普通/全屏、线路浮层、1.25×速度、展开简介。
- `lintDebug` 无错误；依赖升级、现有组件参数风格及 TV banner 尺寸等警告保留，未通过关闭检查来隐藏。

本轮过程中修复了首页分类共用父容器导致串区、初次焦点请求早于布局、搜索重定向处理、活动页销毁后的历史保存、旧分页请求取消、换源未出首帧时的活动媒体身份问题。

## 播放解析

`encrypt=3` 的 BBA / rrmj / NBY 路径只接入已观察的同站 Artplayer 入口。隐藏 WebView 无原生 JS bridge，禁用文件/内容访问、混合内容和未经用户手势的播放，拒绝页面权限；只从 video 元素取得具有时长和元数据的 HTTPS 地址。网页在移交 Media3 前停止并销毁。原生媒体请求携带 WebView UA 和解析页 Referer，未复制账号 Cookie。

HD2 实测在 Media3 1.7.1 的 `HevcConfig` 读取 prefix SEI 时因空 layerInfo 越界；对 rrmj 使用 API 30+ 系统 `MediaParserExtractorAdapter` 后通过。该选择依据 [AndroidX 官方源码](https://github.com/androidx/media/blob/1.7.1/libraries/extractor/src/main/java/androidx/media3/extractor/HevcConfig.java) 与本地原生堆栈，不宣称所有 HEVC 或 provider 已兼容。API 26–29 沿用 Media3 默认提取器，HD2 同类样本可能失败。

## 截图

截图由 Android 测试保存，均为真实应用画面。部分列表只显示视口内区域，以下不是长页拼接。

| 页面 | 截图 |
| --- | --- |
| 首页 | [home](screenshots/home.png) |
| 首页聚焦展开 | [recent-focused](screenshots/recent-focused.png) |
| 分类榜 | [category](screenshots/category.png) |
| 目录 | [catalog](screenshots/catalog.png) |
| 年份浮层 | [filter-year](screenshots/filter-year.png) |
| 搜索 | [search](screenshots/search.png) |
| 历史 | [history](screenshots/history.png) |
| 收藏空状态 | [favorites](screenshots/favorites.png) |
| 站点设置 | [settings](screenshots/settings.png) |
| 全屏播放器 | [player-fullscreen](screenshots/player-fullscreen.png) |
| 普通播放器 | [player-normal](screenshots/player-normal.png) |
| 多线路 | [player-sources](screenshots/player-sources.png) |
| 播放速度 | [player-speed](screenshots/player-speed.png) |
| 展开简介 | [player-description](screenshots/player-description.png) |

## 重现

```sh
source scripts/android-env.sh
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e liveLibvio true \
  -e class com.libvio.tv.LivePlaybackTest,com.libvio.tv.LiveCatalogTest,com.libvio.tv.TvJourneyTest,com.libvio.tv.TvPlayerJourneyTest \
  com.libvio.tv.test/androidx.test.runner.AndroidJUnitRunner
```

设备序号按本机实际连接修改。实站测试会请求公开站点并播放样本，普通单元测试不会联网。调试 APK 与 SHA256SUMS 在本机 `artifacts/prototype/`，不提交 APK，不建立正式 Release。

## 未覆盖

Google TV / Chromecast 实机、长播稳定性、HD10 / 其他 provider、API 26–29、完整断网/入口失效/快速连切故障注入、强杀进程恢复、旧路由别名恢复、1.3 倍字体、中文系统键盘实机输入和完整遥控器焦点遍历。正式签名、更新服务与品牌图标还未配置。
