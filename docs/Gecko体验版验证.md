# CI 固定签名验证（2026-10-27）

- 已配置仓库 Secret `ROCKS_ANDROID_SIGNING`；未将配置或私钥写入公开源码、验证记录或聊天。
- 重跑 Gecko 0.2 运行时代码 `17db69bb43fec62c74572ddd78b37df37bb68aaf`，CI 第 2 次运行：[构建与签名校验](https://github.com/zhao-yc/rocks/actions/runs/39631410714/attempts/2)，job `121966301433`，全部成功。
- 私密配置准备阶段验证了 keystore 内证书；APK 编译后再次使用 `apksigner verify --verbose --print-certs`，实际单一签名证书与固定指纹 `61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf` 一致。签名校验步骤执行成功，没有被跳过。
- 扩展脚本测试、JVM 测试、lint、APK 编译、签名校验、产物上传及临时密钥清理均通过。本次不是工作区补签，签名由 GitHub Actions 读取仓库 Secret 完成。
- 本次已签名 APK 的 Actions 产物：`rocks-gecko-demo-apk`，artifact ID `12704198585`，ZIP SHA-256 `ebfc07ddb9525d82c7857a67314e5ac8e7906bc788cee8db94fd262628d7ef97`，保留至 2026-11-03（以 GitHub 实际显示为准）。ZIP 摘要不等同于其中 APK 摘要。
- 本次仅验证 CI 自动签名，没有发布新版本或替换既有 Release APK。以下 2026-10-21 的手动签名说明为历史记录。

---

# Gecko 0.2 开发预览验证（2026-10-21）

- 运行时代码提交：`17db69bb43fec62c74572ddd78b37df37bb68aaf`，版本 `0.2.0-gecko` / versionCode 2。
- Gecko CI：<https://github.com/zhao-yc/rocks/actions/runs/39631410714>。核心 23 项、应用与共享数据层 24 项 JVM 测试、2 项扩展脚本逻辑测试通过；lint 0 错误、11 警告、2 提示；APK 编译通过。
- 原 WebView 浏览器回归：<https://github.com/zhao-yc/rocks/actions/runs/39631410735>。测试、lint、应用与测试 APK 编译通过。
- 固定证书与 0.1 相同：`61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`。可覆盖安装 Gecko Demo 0.1，保留扩展及其设置。
- APK：`rocks-gecko-0.2.0.apk`，164755642 字节，SHA-256 `77d72c9149562bc8c2b362758fe3e2410d0ede6e80468a77e88800b9906f62fb`。
- 已校验包名 `org.rocks.browser.geckodemo`、版本、ARM64 Gecko 引擎、Paper manifest、ZIP 完整性及实际签名。CI Secret 尚待配置，本次继续使用受控工作区保存的固定密钥签名。
- 公开下载：<https://github.com/zhao-yc/rocks/releases/download/v0.2.0-gecko/rocks-gecko-0.2.0.apk>。发布任务 <https://github.com/zhao-yc/rocks/actions/runs/39633251453> 完成，Release 公开资产的摘要与大小匹配，匿名下载已取回 ZIP 文件头。
- 功能与手机验收步骤见 [工程说明](../android/gecko-demo/README.md)。重启仅恢复网址、标题、当前标签及浏览器设置；不恢复表单、滚动位置和前后退栈。下载过程中需保持应用运行。
- 未执行真机/模拟器端到端验收。下载、文件上传、系统选择器生命周期及手势体验仍需手机验证。扩展脚本测试不代替 GeckoView 中的实际扩展安装与面板测试。

---

# Gecko 扩展体验版验证（2026-10-21）

- 源码提交：`9e18264adae5aea38bf1667963f0a4cb024707b3`，独立工程 `android/gecko-demo`。
- Demo CI：<https://github.com/zhao-yc/rocks/actions/runs/39615229261>。核心测试、4 项 Demo 策略测试、2 项扩展脚本逻辑测试、lint 和 APK 编译通过。
- 原浏览器回归 CI：<https://github.com/zhao-yc/rocks/actions/runs/39615228948>，单元测试、lint、应用与测试 APK 编译通过。
- CI Secret 尚未配置，CI 产出未签名中间包；本次在受控工作区使用已保留固定开发密钥签名，未公开私钥。
- 固定证书 SHA-256：`61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`。
- APK SHA-256：`742aec33cf2de22db9301db2c4da0af3851b09b2f29aaed6a024da2f10b78a43`，164497478 字节。
- 已验证 APK ZIP、包名 `org.rocks.browser.geckodemo`、versionCode 1、versionName `0.1.0-gecko`、ARM64 Gecko 引擎、随包 Paper 扩展及签名。发布任务再次校验哈希、包名和固定证书。
- 公开下载：<https://github.com/zhao-yc/rocks/releases/download/v0.1.0-gecko-demo/rocks-gecko-demo-0.1.0.apk>。GitHub Release 资产摘要匹配，并已实际通过公开地址取回 ZIP 文件头。
- 未执行真机/模拟器端到端测试。扩展脚本测试使用隔离的 WebExtension API 模拟上下文，不能代替 GeckoView 中的插件安装、面板与手势真机验收。

## 手机验收

1. 与原 Rocks 同时安装，确认两者数据互不覆盖。
2. 在底部地址栏缓慢拖动、短滑取消、快速切换及测试首尾标签；检查网页滚动位置保留。
3. 扩展中心安装 Rocks Paper，打开 Example.org，打开面板切换背景与间距，并测试禁用、启用、卸载。
4. 联网安装 Dark Reader / uBlock Origin，核对权限确认、网页效果及当前标签面板。拒绝授权应取消安装。
5. 重启应用检查扩展和设置保留。Demo 不持久化标签，启动后恢复为默认体验页。
