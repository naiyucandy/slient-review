# Silent Review

IDEA 内的静默提交代码审查插件。按已有规则审查 Git 提交，支持多仓库、通用关联文件上下文和可追溯报告处理，附带像素女仆状态提示。

![动作预览](docs/artwork/preview.gif)

## 功能

- 多 Git 根项目：自动识别当前项目结构下的全部仓库并逐仓库准备范围；单个仓库失败不阻断其他仓库。
- 文件级纳入/排除与冻结提交，报告保留仓库、提交和审查范围。
- 通用关联上下文：按规则引用或 `contextFiles` 声明读取同一提交中的配套文件，不固化业务规则。
- 任务→问题多级目录，父节点单页完整报告，子节点仅含规则、问题代码和问题描述；点击代码定位项目源码，支持逐问题Approve/撤销。旧报告与审计历史保留。
- API Key 安全存储与配置状态提示，可主动创建可编辑的默认规则模板；规则集文件支持浏览选择，并可在系统文件管理器中打开。
- 小人点击切换面板显隐，支持四边吸附，五组像素动画共用呼吸节奏；显隐开关只在设置中配置。

## 安装与使用

源码仓库不包含插件安装 ZIP。安装包通过 [Releases](https://github.com/naiyucandy/slient-review/releases) 单独发布；尚未发布时可按下方步骤自行构建。

当前版本为 **1.1.5**，安装范围从 **IDEA 2024.1（241）起**，不再限制到 2024.3。新旧 IDE 共用 `silent-review-1.1.5.zip`。在 IDEA 的 **设置 → Plugins → 齿轮 → Install Plugin from Disk** 中选择插件 ZIP，按提示重启。

1. 打开包含一个或多个 Git 根的项目。
2. 在 **设置 → 静默代码检测** 创建或浏览选择规则并检查，填写 AI 接口、模型和 API Key，确认发送授权后应用。
3. 在 **Silent Review → 审查范围** 准备当前提交，自动覆盖当前项目识别到的全部仓库；确认纳入文件后开始审查。
4. 在 **报告与处理** 选择父节点阅读一页完整报告，选择子问题查看规则、可点击代码和描述；用右下角Approve确认/撤销。取消重复的处理按钮与仓库选择器。

审查对象是已提交快照。未提交的代码不会被悄悄混入，IDEA 提交窗口的 included 选择也不会被插件修改。

`examples/rules/rules.example.json` 是通用 Java 控制台输出 / JavaScript eval 示例，不代表完整业务规范。已有规则不会被模板覆盖。

## 构建

需要 JDK 21。工程自带 Gradle Wrapper，首次构建会下载 Gradle、IntelliJ SDK 和依赖。

Windows：

```powershell
.\gradlew.bat test buildPlugin verifyPluginStructure
```

macOS / Linux：

```bash
bash gradlew test buildPlugin verifyPluginStructure
```

默认编译 SDK 固定为 IDEA Community 2024.1，输出 Java 17 字节码，避免使用新版 API 后让旧版无法运行。可用本机 IDEA 自带的 JDK 21 启动构建：

```powershell
.\scripts\dev.ps1 -JavaHome '<JDK_21_HOME>'
```

安装 ZIP 输出到 `build/distributions/`。`-IdeaHome` 只提供构建 JDK；若需复用下载好的 2024.1 SDK，另传 `-PlatformPath`。旧参数 `-Compatibility2024` 保留接受，所有构建都输出统一的兼容包。

自动化回归、插件结构和跨版本二进制检查的实际结果见 [兼容说明](docs/COMPATIBILITY.md)。真实模型判断和日常 IDE 窗口需安装验收。

## 文档

- [安装与验收](docs/TRY-2024.md)
- [报告浏览与逐问题确认](docs/REPORTS.md)
- [IDE 兼容范围与验证](docs/COMPATIBILITY.md)
- [通用关联上下文与规则声明](docs/RULE-CONTEXT.md)
- [功能验收示例与小人状态](examples/functional-test/README.md)
- [本轮需求范围](docs/SPEC-1.1.md)
- [像素素材记录](docs/ARTWORK-PIXEL.md)

## 数据与边界

API Key 通过 IDEA PasswordSafe 保存，不写入项目 XML。代码和规则仅在用户配置并授权后发送到所选 AI 接口。

插件不修改源码、不阻塞提交、不自动 fetch/pull/push；自动检查仅接入 IDEA 提交成功回调，不监听终端提交。读取使用严格 UTF-8，大小、批次和关联上下文有上限；失败与未完成范围明确显示。模型判断可能存在误报或漏报。

仓库保留 [MIT LICENSE](LICENSE)。原始素材相关许可与来源说明见 [像素素材记录](docs/ARTWORK-PIXEL.md)。本项目不是 DeepSeek 或 JetBrains 官方产品。

1.1.5在1.1.4基础上精简报告界面，默认使用当前项目仓库。分支范围/Diff位于审查文件右键菜单，报告导出/清理位于任务目录右键菜单。
