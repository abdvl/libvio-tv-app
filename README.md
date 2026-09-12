# LIBVIO TV

面向 Google TV / Android TV 的 LIBVIO 原生客户端项目。交互和布局沿用 [Olevod TV](https://github.com/abdvl/olevod-tv-app) 的实践，主色调改为淡蓝色。

**当前阶段：已完成实站调研、实施设计与页面检查工具。Android App 尚未实现，暂无 APK。**

## 计划功能

- 无需登录；观看历史、断点续播、收藏保存在本机。
- 从 [永久入口](https://libvio.lol/) 发现可用站点，记住可用 host，并提供故障恢复与手动选择。
- 首页、电影、电视剧、纪录片、动漫、综艺、筛选目录、中文搜索。
- 原生播放器、遥控器操作、多线路选择、十集分组、全屏覆盖控制。
- 沿用 Olevod 的完整海报、焦点恢复、最近五部＋全部历史布局，焦点色为淡蓝色 `#9BD7FF`。

## 调研与实施

- [实站调研和播放链路](docs/RESEARCH.md)
- [架构、页面、交互与验收设计](docs/DESIGN.md)
- [分步实施计划与当前进度](docs/EXECUTION_PLAN.md)
- [视觉参数](docs/design/tokens.json)
- [2026-09-12 验证记录](docs/verification/2026-09-12.json)

当前重点是验证 **动态片源解析 → Media3 真实播放 → 换源续播**，然后移植完整 TV 界面。桌面浏览器已经验证两条样本线路，Android / Google TV 原生播放仍待实施和验证。

## 页面契约检查工具

需要 Python 3.9+，仅使用标准库，无第三方依赖。

```sh
python3 -m unittest discover -s tests -v
python3 scripts/probe_site.py --live --detail-path /detail/5813548.html --limit 3 --output .tools/research/latest.json
```

不加 `--live` 不访问网络。该工具只读公开 HTML，检查入口、片单、线路和播放配置；不会执行播放器脚本或下载视频，不验证 Android 播放。输出不含媒体直链或 Cookie，临时文件目录不提交。

## 许可

[MIT License](LICENSE)。影片、海报和网站品牌资产的权利归各自权利人，代码许可不涵盖这些内容。
