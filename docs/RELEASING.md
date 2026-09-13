# 正式签名与发布

LIBVIO 使用独立发布身份，和 Olevod 或 Android 调试签名分开。正式签名私钥、密码仅位于被 Git 忽略的 `.secrets/`，不能上传到仓库或 Release。首次创建后应安全备份该目录；后续发布必须恢复同一身份，不能重新生成代替。

## 构建

配置 JDK 17 / SDK 35 后：

```sh
source scripts/android-env.sh
# 仅首次建立发布身份；若已有备份，应恢复备份。
python3 scripts/init-release-signing.py
bash scripts/build-release.sh v0.1.2
```

脚本要求 tag 与 versionName 完全一致、发布目录尚不存在、有签名配置；执行 assembleRelease、testReleaseUnitTest、lintRelease，验证签名后把 ARM32 / ARM64 两个包和 SHA256SUMS.txt 保存到 `artifacts/releases/<tag>/`。已有目录和签名文件会被保护，脚本拒绝覆盖。

当前正式签名证书 SHA-256（公开指纹）：`8a87027c873b1ee066828580088260ffe3f71ff374d192c192afdaab324e02fa`。

## 正式包设备验证

```sh
./gradlew -PtestBuildType=release assembleReleaseAndroidTest
adb -s "$EMULATOR_SERIAL" install artifacts/releases/v0.1.2/libvio-tv-v0.1.2-arm64-v8a.apk
adb -s "$EMULATOR_SERIAL" install app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -s "$EMULATOR_SERIAL" shell am instrument -w -r   -e liveLibvio true -e seedEmulatorFavorite true   -e class com.libvio.tv.SoftwarePlaybackTest,com.libvio.tv.ReportedPlaybackTest   com.libvio.tv.test/androidx.test.runner.AndroidJUnitRunner
```

使用干净的测试模拟器。`seedEmulatorFavorite` 只允许模拟器；不能在用户真机上清除数据来解决签名冲突。此前开发包与正式签名不同，正式 APK 的首次迁移需要另行保存与恢复本地数据。

## 发布与核对

1. 更新版本、Release 说明、验证记录与 Token history 截止点，提交并推送源码。
2. 将相同提交标记为 `v<versionName>`，发布正式 GitHub Release。
3. 附加该版本两个 ARM APK 和 SHA256SUMS.txt，不附带调试包、测试包、私钥或日志。
4. 独立读取远程分支与标签、Release 状态及附件集合；从公共 URL 下载两个 APK 与校验文件，对比大小和 SHA-256。
5. 在验证记录中记录结果；网页上传进度或本地提交不是已发布的证明。

应用内自动更新尚未实现。未知工具和模型费用在 Token history 标注为未统计；下一次从上次固定截止点增量计算。
