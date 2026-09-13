# v0.1.2 正式包验证

应用 `com.libvio.tv`，versionName `0.1.2`，versionCode `4`。发布身份与调试版、Olevod 分开。[机器可读清单](manifest.json) · [Release 说明](../../releases/v0.1.2.md)

## 构建与签名

`bash scripts/build-release.sh v0.1.2` 完成 assembleRelease、21 项 Release 单元测试和 lintRelease。两个 APK 均通过 apksigner v2 校验；证书 SHA-256 为 `8a87027c873b1ee066828580088260ffe3f71ff374d192c192afdaab324e02fa`。私钥与密码没有提交或上传。

| 附件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `SHA256SUMS.txt` | 196 | `d0ac291c05a8792c49dc90f50a4ca1858489e3a0847850b1edc11482d1fdbb97` |
| `libvio-tv-v0.1.2-arm64-v8a.apk` | 67,083,088 | `4863b80b560451991860ebc3324246f45adada6994f5bf6762c15012f57c46a8` |
| `libvio-tv-v0.1.2-armeabi-v7a.apk` | 54,391,128 | `d3d023bfe379d190d0697b5f050e3adaf883c3ee7cac11eb127b8898632d8a5d` |

## 正式包设备测试

API 34 / arm64 TV 模拟器安装了 Release APK 和同签名测试包，4 项实站回归全部通过（187.322 秒），见 [原始结果](emulator-release-results.txt)。覆盖原 HD3 软件解码连续一分钟、暂停中原生/软解往返、跳转、1.25 倍速、全屏/普通布局保持当前媒体、收藏再打开及海洋奇缘 HLS 的持续原生视频输出。

此前 Chromecast 真机的同一播放实现已完成 4 项回归及原生 Surface 补验，见 [真机证据](../software-decoding/README.md)。真机仍保留 0.1.2-dev 调试安装；本次没有通过卸载清除其收藏与历史来迁移正式签名。正式 ARM32 包完成构建、架构和签名核验，不能把 arm64 模拟器结果写成该 APK 的真机验收。

全站格式、整集热稳定性、API 26–29 和主观音画同步仍未完整验收。32 位 ARM 软解关闭去块滤波以改善实测吞吐，压缩块可能更明显。

## 发布状态

[v0.1.2 已正式发布](https://github.com/abdvl/libvio-tv-app/releases/tag/v0.1.2)，发布时间 2026-09-12 20:23:21 PDT。GitHub API 确认不是草稿或预发布。

- 发布时远程 main 与 v0.1.2 均指向 `394b11d30e6ada6e0e500525ff170823414f63ab`；后续 main 的核验文档提交不会移动已发布标签。
- Release 附件集合恰为上述两个 ARM APK 和 SHA256SUMS.txt；版本、字节数和 GitHub digest 均与本地产物一致。
- 2026-09-12 20:24:43 PDT 从三个公共下载 URL 独立重新下载，两个 APK 的 SHA-256 均匹配已发布的 SHA256SUMS.txt，同时匹配本地产物；校验文件字节内容也完全一致。
- 网页显示为 Latest，发布 API、Git refs 和公共下载证据分别核对。完整结果保存在 manifest.json。

本次发布与核算操作位于 Token history 固定截止点之后，将留待下一次增量统计。
