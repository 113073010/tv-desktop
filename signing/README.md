# signing/

`tvdesk.jks` 是五个插件和两个独立应用共用的签名 keystore：

| 项 | 值 |
|---|---|
| alias | `tvdesk` |
| storepass / keypass | `android` |
| 算法 | RSA 2048 |
| 有效期 | 10000 天 |

`*.jks` 被 `.gitignore` 忽略，**仓库里没有这个文件**。

## 首次构建会自动生成

`scripts/build-plugin.ps1` / `scripts/build-app.ps1` 在找不到 `signing/tvdesk.jks` 时
会现场用 `keytool` 生成一个，所以新克隆开箱即可构建。但它和原来设备上装的那套签名
**不是同一把钥匙**。

## 换机器构建会怎样

Android 只按签名判断是不是同一个应用：

- 插件：桌面不校验签名，换钥匙也能用（只是和旧 APK 签名不同）
- 独立应用：`adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
  必须先 `adb uninstall <包名>` 再装，**该应用的数据会丢**

所以要把旧机器上的 `signing/tvdesk.jks` 复制到新机器，别让它自动生成新的。
这个文件也不要提交到仓库、不要外发。

> 以前 live 插件的构建脚本会在 `plugin-live/build/` 下生成 keystore，其他插件去引用它，
> 导致「新克隆必须先构建一次 live」。现在统一到这里，依赖关系已经去掉。
