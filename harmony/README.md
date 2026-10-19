# 原生鸿蒙工程

独立 Stage 工程，采用 ArkTS、ArkUI、ArkWeb，不是 Android APK 壳。已有 UIAbility、资源、底部地址栏、基本导航、证书取消、权限拒绝和同格式 JSON 的 Preferences 适配器。

**尚未编译或运行。** 本机没有 DevEco Studio/Harmony SDK。初始目标 HarmonyOS 5.0.0 / API 12、Hvigor 模型 5.0.0；须在真实 SDK 中核对，不伪造 HAP 或成功记录。无本机路径、签名配置或证书。

## 首次验证

1. 用户确认安装 DevEco/匹配 SDK 并审阅接受许可。
2. IDE 打开本目录，核对产品 SDK/Hvigor 并同步依赖。
3. 执行 Build HAP。若采用 IDE 自带入口，命令形态为 `hvigorw.bat --mode module -p module=entry@default -p product=default -p buildMode=debug assembleHap`。入口由实际 DevEco 提供，本仓库不伪造 wrapper。
4. 用户配置本地设备签名，上手机验证导航、返回、证书与会话恢复。签名文件不提交。

| 阶段 | 交付与验收 |
| --- | --- |
| H1 | 真实 SDK 首编译，修复 ArkWeb API、资源、安全区和 Preferences 诊断 |
| H2 | 24 标签状态机、Controller 池、总览与进程恢复；fixture 对照 |
| H3 | 书签/历史/设置；国际域名和 IPv6 一致性；目前仅严格 ASCII 地址，与 Android 尚非完全等价 |
| H4 | 下载、文件选择、分享、全屏、逐来源运行时权限和外部链接确认 |
| H5 | 国内网络真机、登录/验证码、性能、可访问性与签名分发 |

目前页面只操作恢复出的活动标签，尚无多标签或书签历史管理 UI。没有无痕入口。数据契约变化必须同步更新 `shared`、Android codec 测试与鸿蒙适配器。
