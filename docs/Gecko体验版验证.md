# Gecko 0.4.2 状态栏与浮栏修正（2026-10-27）

- 源码提交：`6e6f25d1d8e1c18b53b12c28b09b3adb93c7a7ec`，版本 `0.4.2-gecko` / versionCode 6。沿用包名和固定证书，可覆盖之前 Gecko 体验版。
- 网页视口移除顶部固定安全区留白，绘制到透明状态栏后方；起始页、错误/加载提示保留各自的状态栏安全位置。系统时间、电量和网络图标保持显示，网站全屏仍隐藏系统栏。
- 状态栏图标根据应用在状态栏后方实际绘制的亮度调整深浅，使用 24×4 内存采样、600ms 限频和明暗滞回。暂停/销毁时停止采样，隐藏状态栏时跳过；图像只用于本地渲染对比度判断并立即回收，没有保存、日志或上传，也没有增加网页脚本、桥或权限。
- 悬浮栏原有 9dp 投影归零，tonal/shadow elevation 均为 0，保留细边框、配色、动画和手势；展开、收起、编辑和查找状态共用该无投影表面。
- [Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/40481647470) 成功：核心 23 项 + 应用/共享数据 36 项 JVM 测试，共 59 项；2 项扩展脚本测试；lint 0 错误、15 警告、2 提示；APK 编译及实际固定签名校验通过。新增 lint 提示为 Bitmap API 的 KTX 写法建议。
- [发布运行](https://github.com/zhao-yc/rocks/actions/runs/40482216534) 再次验证源构建、产物摘要、实际签名、包名、版本及引擎后成功发布。签名证书 SHA-256 仍为 `61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`，私密配置未公开。
- 公开下载：<https://github.com/zhao-yc/rocks/releases/download/v0.4.2-gecko/rocks-gecko-0.4.2.apk>，164944255 字节，SHA-256 `b6f3aa9d539a523ba08e153f5e2e7894067c8e173ddc585a44e00490fe8b1915`。Release 已公开，匿名下载已实际取回 ZIP 文件头。
- 没有执行真机/模拟器视觉验收；透明状态栏、挖孔/横屏、深色网页图标对比度、键盘和底栏投影效果仍需手机确认。步骤见 [工程说明](../android/gecko-demo/README.md)。

---

# Gecko 0.4.1 日常浏览功能验证（2026-10-27）

- 运行时代码：`d40d12820d5ba255cc23cd4a51452571cf967bff`，版本 `0.4.1-gecko` / versionCode 5，包名 `org.rocks.browser.geckodemo`，沿用固定开发证书，可覆盖现有 Gecko 体验版。
- 新增默认浏览器系统选择器及实际角色状态；HTTP/HTTPS 外部链接冷/热启动接收与恢复期间的有界队列；Gecko 原生页面查找与匹配计数；当前标签桌面 UA/视口切换；网站相机、麦克风及定位确认、Android 运行时权限、采集提示和网站定位重置。沉浸式悬浮栏及扩展功能保留。
- 网站新授权检查当前前台标签、HTTPS 自身来源、完整网址和文档版本。系统权限窗口可以暂停/停止宿主，结果需返回前台并重新核对后才处理；取消、换页和换标签不会放行旧结果。定位选择由 Gecko 按站点保存，重置网站不会撤销 Android 应用权限。桌面模式仅本进程当前标签生效；重启回到移动版。跨站 iframe、扩展页及屏幕采集未实现。
- [最终 Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/40476377627) 成功：核心 23 项 + 应用/共享数据 36 项 JVM 测试，共 59 项；2 项扩展脚本逻辑测试；lint 0 错误、13 警告、2 提示；APK 编译、固定签名校验、产物上传和临时密钥清理成功。
- 4 项入口/权限策略测试覆盖外部链接过滤、来源归一化、同网址重载和换标签的授权隔离、一次性两阶段授权；4 项实际查找控制器测试覆盖后台结果、延迟派发后的恢复、换标签迟到结果和清空关键词。查找引擎在测试中替换为受控测试实现，不代表真实设备/Gecko 页面端到端验收。
- CI 自动签名证书 SHA-256：`61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`，与之前 Gecko 体验版一致。未读取或公开任何私钥内容。
- [发布运行](https://github.com/zhao-yc/rocks/actions/runs/40476842310) 成功，校验了源构建、artifact ZIP 摘要、实际签名、包名、版本、Gecko 库与 Paper 扩展；本轮额外修复了后台查找等待状态后发布 0.4.1。
- 公开下载：<https://github.com/zhao-yc/rocks/releases/download/v0.4.1-gecko/rocks-gecko-0.4.1.apk>，164938403 字节，SHA-256 `0e7da882fef1b3189f897c51a2047351fbd384ac1e21f11a0acace5f74a7e40a`。公开资产元数据与版本匹配，匿名下载已实际取回 ZIP 文件头。
- 未执行真机/模拟器端到端测试。默认浏览器选择器、其他应用唤起、实际摄像头/麦克风/定位、Android 权限窗口及横竖屏生命周期仍需手机验收，步骤见 [工程说明](../android/gecko-demo/README.md)。

---

# Gecko 0.3 沉浸式交互验证（2026-10-27）

- 运行时代码提交：`b6fc7b441d9151052bd23648887d80497b85365f`，版本 `0.3.0-gecko` / versionCode 3，沿用 `org.rocks.browser.geckodemo` 包名与现有固定证书，可覆盖安装 0.1/0.2 Gecko 体验版。
- 本轮移除浏览器标题行，改为不占用页面布局行的底部悬浮栏；向下浏览收起为网址胶囊，向上浏览/轻点胶囊展开。保留 Rocks 配色与轮廓，菜单集中扩展、书签、分享及浏览记录。Android 系统状态栏仍保留。
- 使用 Gecko CompositorScrollDelegate 的用户滚动来源，结合方向阈值过滤抖动、脚本移动和缩放。编辑、键盘、面板、TalkBack/大字体保持展开；两种状态均保留横向切标签。通过底部遮挡提示放置网站固定控件，不改变 GeckoView 物理尺寸。
- [Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/40470046352) 全部成功：核心 23 项 + 应用/共享数据 28 项 JVM 测试（包含 4 项新滚动策略测试）、2 项真实扩展脚本逻辑测试、lint、APK 编译和实际签名校验。lint 为 0 错误、13 警告、2 提示；依赖版本/目标 SDK、既有样式建议及两个本轮样式/未用资源提醒未阻止构建。
- APK 由仓库 Secret 在 CI 中直接签名。证书 SHA-256 为 `61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`，与 0.1/0.2 一致；临时私钥清理步骤成功。
- [发布运行](https://github.com/zhao-yc/rocks/actions/runs/40470395499) 校验源构建和代码提交、固定 Actions artifact ZIP 摘要、APK 实际证书、包名、版本、ZIP、Gecko 引擎和 Paper 扩展后发布，没有使用公共临时中转服务。
- 公开下载：<https://github.com/zhao-yc/rocks/releases/download/v0.3.0-gecko/rocks-gecko-0.3.0.apk>。Release 为公开预览版；匿名下载已实际取回 ZIP 文件头。APK 为 164806479 字节，SHA-256 `6b45d5d3fe758bd86191365aae02c7341b3ae93caf7a864db49ce5e9c58c859e`，以 GitHub 公开资产元数据和随包摘要文件核对。
- 新的浮层动画、网页固定底栏、输入法、横屏、系统手势区及 TalkBack 的端到端体验仍待手机验收。本环境未执行真机或模拟器测试。步骤见 [工程说明](../android/gecko-demo/README.md)。

---

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
