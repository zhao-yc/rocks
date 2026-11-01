# Gecko 0.4.6 网页真实下穿状态栏（2026-11-01）

- 用户明确要求网页真实进入状态栏背后。将网页 View 改为完整窗口高度，首屏下移安全区高度，随真实物理滚动逐渐移至窗口顶部，回顶恢复；滚动期间不改变 View 高度。平移同步 Gecko 屏幕坐标，并补偿固定底部控件的裁切量。
- 模糊层与底下真实 TextureView 采用同一坐标，不再将一行或窄条截图拉伸延展到状态栏。模糊图不烘焙不透明底色，通过独立透明遮罩与真实网页过渡；只有首屏确实无网页的空隙填色。底栏及其手势保持原行为。
- 本机 9 项顶部策略、2 项扩展脚本测试通过。[候选源码及原生模拟器构建](https://github.com/zhao-yc/rocks/actions/runs/41283618473)、发布源码 `fed69e375952438e79a3baa7a51ac34e67f8feb9` 的 [main Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/41284651959) 均成功，覆盖规定的 `:core:test testDebugUnitTest lintDebug assembleDebug`、instrumentation APK 与实际固定签名。
- 新增真实几何设备回归：旧版 0.4.5 因没有完整窗口 View 高度触发预期断言失败；新版 x86_64 原生包、实际 ARM64 手机包均通过首屏避让、真实 View 滚至屏幕 y=0、前后高度一致及回顶恢复，原始返回码 `-1`。ARM64 的 3 项显示连接释放回归亦通过。
- 原生 Android 15 模拟器完成百度首屏和实际下穿截图、英纵固定页头滚动后菜单按新坐标可点、真实软键盘、深色夹具浅色图标、网站全屏及退出恢复、旋转与后台返回，无新增应用崩溃。全屏首次系统引导关闭后验证退出；测试网页的标准全屏 API 不进入产品代码。手机挖孔、Dark Reader 扩展和流畅度仍需单独确认。
- 版本 `0.4.6-gecko` / versionCode 10，包名和证书不变，可覆盖升级。[发布运行](https://github.com/zhao-yc/rocks/actions/runs/41284870555) 校验源提交、产物 ZIP 摘要、证书、包名、版本与 ARM64 引擎，上传后完整重下载核验再公开。
- [下载 APK](https://github.com/zhao-yc/rocks/releases/download/v0.4.6-gecko/rocks-gecko-0.4.6.apk)：165009717 字节，SHA-256 `f581f4b135314729b635ea9691f5572a9013f217849f3d808153d1dac6b6ed73`。main 实际 APK 与本机已安装 ARM64 回归包摘要一致；公开摘要与文件头匿名核验通过。未增加权限、网页脚本或桥，未安装新 SDK/镜像，未生成或复制签名私钥。

---

# Gecko 0.4.5 顶部模糊透明渐变（2026-11-01）

- 用户确认目标为本轮带灵动岛的图2：网页颜色模糊透出，顶部下缘逐渐透明。增加模糊纹理可见度及 18dp 透明尾部，回顶消退，不改变网页位置、顶部安全区、底栏或触摸路径。
- 实际截图暴露原取样路径返回 `ERROR_SOURCE_NO_DATA (3)`，纹理始终为空。补充真实 TextureView 帧更新驱动及受限分辨率备用读取；优先局部 PixelCopy，失败时最多读取 128×384 缩略图并立即裁出顶部、回收整图。不读应用窗口，保留 67ms 限频、单一在途与迟到结果隔离，不新增网页脚本、桥或权限。
- 本机 8 项顶部策略与 2 项扩展脚本测试通过。[取样修复及原生模拟器构建](https://github.com/zhao-yc/rocks/actions/runs/41267998018)、发布源码 `2c999032bdf662b54f854b7a10f08c2611991893` 的 [main 完整 Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/41268458379) 均成功，含 `:core:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 与固定签名。
- 无脚本 CSS 动画夹具的设备对照：旧取样实现为 0 帧、回归失败；修复后的原生 x86_64 包与实际 ARM64 手机包均收到至少 3 帧独立处理纹理、返回码 `-1`。ARM64 的 3 项显示连接释放回归同样通过，保留 0.4.4 闪退修复。
- 原生模拟器实际检查百度同位置滚动、蓝色按钮与图标的模糊延展、透明尾部、回顶消退、英纵菜单打开/关闭、滚动、旋转和后台返回，无新增崩溃。手机首帧、挖孔、渐变强度及流畅度仍待用户确认。
- 版本 `0.4.5-gecko` / versionCode 9，沿用包名与固定证书。[发布校验](https://github.com/zhao-yc/rocks/actions/runs/41268665688) 成功，核对来源、签名、包名、版本、引擎、ZIP 摘要，上传后完整重下载核验再公开。
- [下载 APK](https://github.com/zhao-yc/rocks/releases/download/v0.4.5-gecko/rocks-gecko-0.4.5.apk)：165006235 字节，SHA-256 `fa67e88a7d4aa8ed21cccd80937441e7ad73674fda03bb3c0e451356075b4ccf`，与本机实际安装测试的 ARM64 包相同；匿名下载文件头及摘要核验通过。固定证书不变，未生成或复制私钥，未安装新 SDK/镜像或接受新许可。

---

# Gecko 0.4.4 闪退修复与本机模拟器验证（2026-11-01）

- 用户报告手机闪退后，在本机已有 Android 15 / API 35 镜像安装公开 0.4.3，复现网页切换和标签恢复中的主线程空指针。根因为 Kotlin 覆写 `releaseDisplay` 的非空检查拒绝了 GeckoView 实际传入的空连接；改用 Java 保留引擎运行时契约及所有权校验。
- 发布源码 `9aee911b19fe3870503baf93b81bcd59f469bad6`，版本 `0.4.4-gecko` / versionCode 8。包名、固定开发证书不变，可直接覆盖安装，不清除浏览数据。
- [Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/41263984717) 成功，覆盖扩展脚本、`:core:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`、实际固定签名及清理。[原 WebView 回归](https://github.com/zhao-yc/rocks/actions/runs/41263984641) 同样成功。
- 新增真实 Gecko 显示连接生命周期 instrumentation：同测试包在原版 0.4.3 上复现相同空指针；功能分支修复包和实际 main ARM64 包均为 3 项通过，原始返回码 `-1`。覆盖安装保留已有标签，未通过清除应用数据规避恢复问题。
- 为排除 ARM 转译/JIT 崩溃，显式构建同运行时代码的原生 x86_64 验证包，[构建与签名校验](https://github.com/zhao-yc/rocks/actions/runs/41264099220) 成功。模拟器完成英纵首页/菜单、百度首页、滚动回顶、横竖屏和后台返回，最终崩溃缓冲区为空。网页 Logo、头像和菜单均位于系统状态栏下方，背景保持白色。
- 不把 ARM64 转译环境判为完整通过：曾捕获 `libndk_translation.so` 原生崩溃。原生模拟器也出现过首帧未绘制/黑帧，触摸后显示正常；首帧性能、手机挖孔/键盘/Dark Reader/全屏及流畅度仍待真机验证。详细证据与边界见 [顶部浏览体验验收](顶部浏览体验验收.md)。
- [发布校验](https://github.com/zhao-yc/rocks/actions/runs/41264521226) 成功：仅使用 main ARM64 产物，核对来源、ZIP 摘要、实际证书、包名、版本及引擎，上传草稿后重新下载完整 APK 核对字节，再公开预览版。
- [下载 0.4.4 APK](https://github.com/zhao-yc/rocks/releases/download/v0.4.4-gecko/rocks-gecko-0.4.4.apk)：164994935 字节，SHA-256 `361f57a55c43c0dcdaa115d57c34a5dc61f3d4f3531f8af456c6afddb6f0603c`，与本机实际安装回归的 main 包相同。公开摘要及 APK/ZIP 文件头已匿名取回核对。签名证书 SHA-256 仍为 `61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`。
- 未下载新 SDK/镜像或接受新许可；未使用本机 Docker；没有生成、提取或落地新的签名私钥。

---

# Gecko 0.4.3 顶部安全区与滚动模糊（2026-10-31）

- 源码提交：`ef9ab46bb796d255c146bf66080f9ed64d92ceff`，版本 `0.4.3-gecko` / versionCode 7；沿用包名 `org.rocks.browser.geckodemo` 与固定证书，可覆盖已有 Gecko 体验版。
- 统一网页与原生起始页的顶部安全区，修正百度入口、英纵 Logo/菜单与系统时间和电量重叠。顶部背景按网页边缘主色衔接，并加入原生滚动模糊过渡；修正 CSS 安全边距重复消费与已加载标签的底色重绘。详细内容与真机待验项见 [顶部浏览体验验收](顶部浏览体验验收.md)。
- [Gecko CI](https://github.com/zhao-yc/rocks/actions/runs/41216388586) 成功：扩展脚本、核心与应用单元测试、lint、APK 编译、实际固定签名校验和临时签名材料清理均通过。[原 WebView 回归](https://github.com/zhao-yc/rocks/actions/runs/41216388521) 亦成功，没有运行可选模拟器任务。
- 用户明确授权公开 APK 后，新增仅手动触发的发布入口；必须引用当前 main 提交已成功且已实际验签的 Gecko 构建。PR 和普通推送不能自动发布，也不恢复已归档的历史发布脚本。
- [发布运行](https://github.com/zhao-yc/rocks/actions/runs/41216657924) 成功：核对源提交、工作流、产物 ZIP 摘要、APK 实际证书、包名、版本、ARM64 Gecko 引擎和 Paper 扩展；草稿上传后重新下载完整 APK 并核对 SHA-256，随后转为公开预览版。
- 签名证书 SHA-256 保持 `61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf`。未生成新密钥，没有将私钥复制到本机或公开资产。
- [公开 APK](https://github.com/zhao-yc/rocks/releases/download/v0.4.3-gecko/rocks-gecko-0.4.3.apk)：164995543 字节，SHA-256 `b095b7450c15a31bc00bf1ecbc13f144e17333a925d3ec5384392094ae87e561`。公开资产元数据与摘要文件一致，已匿名取回 APK/ZIP 文件头，确认下载地址有效。
- 尚未执行本轮真机视觉验收；本包交给用户在手机上测试百度、英纵以及深浅色切换、横屏、键盘和网站全屏返回。

---

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
