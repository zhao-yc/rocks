# Rocks 开发约定

- 产品面向中国用户优先，默认中文资源，同时维护英文资源；README 保留英文。无应用广告、推广位、资讯流或遥测。
- Android 主线为 Kotlin/Compose/GeckoView（`android/gecko-demo`，沿用体验版包名与签名以便覆盖升级）；原 `android/app` WebView 工程保留作回归参考；鸿蒙为 ArkTS/ArkUI/ArkWeb。不要改为跨平台网页壳。
- `core` 不依赖 Android；`shared/browser-data.schema.json` 与 fixture 是双端数据契约。修改需同步模型、codec、测试和鸿蒙适配计划。
- Android 常规检查：`gradlew.bat :core:test :android:app:testDebugUnitTest :android:app:lintDebug :android:app:assembleDebug`。仅 JVM 可用时运行 `--configure-on-demand :core:test` 并明确未执行的检查。
- 不绕过证书，不自动授予网站权限，不添加任意 Intent 跳转或 native JavaScript bridge。未验证隔离前不提供无痕入口。
- 不自动安装移动端 SDK、接受新许可或扩大账号权限。CI 构建使用 runner 预装 SDK，下载系统镜像的模拟器任务须显式选择。缺工具时保留真实诊断，不能假报编译运行通过。
- 会话写入需原子/有序，未知格式保留原件。普通标签共享网站存储；清空历史不能声称清除全部网站数据。
- 只提交源码、测试、契约与文档；不提交本机路径、凭据、用户浏览记录、签名、日志或生成产物。项目自身许可证尚待所有者选择，不擅自添加。
