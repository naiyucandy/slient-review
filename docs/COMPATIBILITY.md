# IDEA 兼容说明 · 1.1.5

`silent-review-1.1.5.zip` 是统一安装包，最低版本为 IDEA 2024.1（平台构建号 241）。2024、2025、2026 系列不再分别提供安装包。

## 编译和安装范围

工程固定以 IDEA Community 2024.1 SDK 编译，并通过 `--release 17` 约束 Java API 和字节码；Gson 使用 2024.1 同版本的 2.10.1 接口。Gradle 本身使用 JDK 21，插件在 IDEA 2024.1 自带的 Java 17 上运行不需要另装 JDK 21。

生成的 `plugin.xml` 使用 `since-build="241"`，省略 `until-build`，解除旧包 `243.*` 的安装限制。以后新平台不会仅因固定的年份上限被拒装；未来平台的实际 API 和界面变化仍需回归验证。

涉及文件、编辑器、Diff 和工具窗口的异步回调使用平台调度，后台读取项目内容根使用读锁。规则文件的 VFS 刷新在后台执行，设置页回调保留当前模态状态，适配新版线程要求。

本轮验证方法及实际结果见 [DEV-010](verification/DEV-010-1.1.5.md)。二进制兼容检查验证类和方法的可用性，真实模型调用、提交回调、操作系统密码库和不同缩放下的窗口仍需要安装验收。

## 复核方法

设置 `JAVA_HOME` 为 JDK 21 后，常规构建会下载最低版 SDK：

```powershell
.\gradlew.bat test buildPlugin verifyPluginStructure
```

已有 2024.1 SDK 时可以传 `-PlocalPlatformPath=<SDK目录>`；新的 IDE 安装目录用于验证，不用于提高最低编译版本。开发脚本的 `-IdeaHome` 提供 JDK，`-PlatformPath` 可指定最低版 SDK。旧的 `-Compatibility2024` 参数仍被接受，所有构建均输出同一个兼容包。

`verifyPlugin` 默认验证编译 SDK。可用多个本地 IDE 做二进制检查，Windows 路径间用分号分隔：

```powershell
.\gradlew.bat verifyPlugin '-PverificationIdePaths=<IDEA_2024_1_HOME>;<IDEA_2024_2_HOME>;<IDEA_2026_2_HOME>'
```

## 从旧包更新

在 IDEA 的“设置 → Plugins → 齿轮 → Install Plugin from Disk”选择新 ZIP，按提示重启即可。不要解压安装包。插件 ID `dev.silentreview.idea` 保持不变，原配置、凭据命名空间和报告处理格式沿用。

截图中的 `0.1.1-dev` 和以前发布的 `1.1.0` 均有 `243.*` 上限；新版 IDE 应换成 1.1.5，旧包本身不会随源码修改自动变化。
