# Android 测试包的固定签名

从 0.2.2-dev 开始使用项目专用私有开发密钥，证书 SHA-256 固定为：

```text
61fd4f1cf391cb98b53030f008aa0dd57dae4a91fac1bf3525f8db013065acaf
```

公开指纹保存在 `android/development-signing.sha256`。私钥及密码不提交 Git、不上传公共 artifact、不放入 Actions 缓存或 Release 附件，也不使用公开测试密钥。

## CI 配置

仓库所有者在 GitHub Settings → Secrets and variables → Actions 中创建唯一一个 Repository secret：`ROCKS_ANDROID_SIGNING`。其值是私有签名配置 JSON，包含 version、keystore_base64、store_password、key_password、key_alias、certificate_sha256。使用已生成并私下保留的原始配置，不重新生成。

`scripts/android_signing.py prepare` 从 Secret 还原 PKCS12 到 runner 临时私有目录，检查证书与固定指纹一致，再向 Gradle 提供签名参数。密码仅经环境变量传递并在日志中屏蔽；PR 构建不接触私钥。

没有配置 Secret 时，CI 只生成标记为 unsigned 的中间包，不产生可分发的随机签名 APK。传入损坏或不匹配的配置会直接失败。显式设备测试使用 `prepare --require`，缺少配置即停止。

正常构建检查应用与测试 APK 的签名；另一个全新 runner 再次构建并核对相同证书。只有通过指纹校验的 APK 才可对外发布。

当前 GitHub 连接读取/写入 Actions Secrets 的权限不足（Secrets API 返回 403）；配置文件已私下准备，但需要所有者手动保存此 Secret。配置完成前，可在持有同一私钥的受控环境中签名 unsigned 中间包并校验固定指纹，不能把临时调试密钥重新用于公开下载。

## 本地构建

使用同一配置运行 prepare，或在构建进程中提供以下环境变量：`ROCKS_ANDROID_KEYSTORE`、`ROCKS_ANDROID_KEY_ALIAS`、`ROCKS_ANDROID_STORE_PASSWORD`、`ROCKS_ANDROID_KEY_PASSWORD`。Gradle 不再自动生成调试签名；无配置时输出 unsigned 中间包。

签名后运行：

```sh
python3 scripts/android_signing.py verify --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner" --apk path/to/app.apk
```

## 旧版本迁移与备份

0.2.0-dev 与 0.2.1-dev 的临时私钥没有保留，不能从 APK 或公开证书恢复私钥。因此固定签名的新包不能覆盖这两个旧包；卸载旧包会清空其应用内浏览数据。此后同包名、同固定证书且 versionCode 递增的版本可以覆盖更新。

私有配置与 PKCS12 需由所有者另作离线加密备份；丢失时停止发布，不能静默换密钥。GitHub Secret 被删除或配置损坏不应触发密钥再生成。
