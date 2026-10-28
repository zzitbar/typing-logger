# 打字记录（TypingLog）

一个只在本机存数据的 Android 输入记录器，用来在华为 Pura 70（HarmonyOS 4.x）上记录你通过华为输入法**上屏**的文字。

- 不需要 root，不需要电脑。
- 不申请网络权限，记录只写进应用私有目录，只能由你自己在界面里查看或导出。
- 密码框由系统屏蔽，读不到也不会记录。

## 原理与边界

输入法把文字提交给输入框时，输入框会发出 `TYPE_VIEW_TEXT_CHANGED` 事件，事件里带变化后的完整文本。本应用用无障碍服务监听该事件，按“应用 + 窗口”聚合，停顿 800ms 或输入框被清空（发送/切走）时写入一条记录。

因此：

| 能记录 | 记录不到 |
|---|---|
| 最终上屏的汉字/英文、时间、所属应用 | 拼音串、候选词、选了第几个候选 |
| 发送瞬间的整段文字 | 被删掉的中间过程 |
| 微信、短信、备忘录、浏览器等大多数输入框 | 密码框、银行类安全键盘、游戏/Canvas 自绘界面 |

个别输入框会在你打拼音的过程中把 `nihao` 这种临时串也报上来，如果两次停顿超过 800ms，可能多出一条拼音记录，属正常现象。

## 目录

```
typing-logger/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/dev/local/typinglog/
        │   ├── MainActivity.kt      界面：状态、开关、查看、导出、清空
        │   ├── TypingLogService.kt  无障碍服务：捕获并聚合上屏文字
        │   ├── LogStore.kt          本地 JSONL 存储与 zip 导出
        │   └── Record.kt            数据结构
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml, themes.xml
            └── xml/accessibility_service_config.xml, file_paths.xml
```

## 构建

环境：Android Studio Hedgehog (2023.1.1) 或更新，JDK 17，Android SDK 34。

1. Android Studio → **Open**，选择 `typing-logger` 目录。
2. 等 Gradle sync 完成（首次会下载 Gradle 8.2、AGP 8.2.2、Kotlin 1.9.22 和 androidx.core）。
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**，产物在 `app/build/outputs/apk/debug/app-debug.apk`。

命令行构建（需本机有 Gradle 8.2+ 与 SDK 34）：

```bash
cd typing-logger
gradle assembleDebug
```

> 本工程生成时没有在这台机器上编译过（原因见会话里的说明），首次构建若有报错，把错误贴回来即可。

## 安装（华为特有）

1. **先退出纯净模式**：设置 → 系统和更新 → 纯净模式 → 退出（需要华为账号密码）。不退出会拦截非应用市场安装包。
2. 把 `app-debug.apk` 传到手机（数据线、华为分享、网盘均可），用文件管理打开安装；提示“未经检测”时选继续安装。
3. 打开「打字记录」，点 **去开启无障碍服务**。如果系统提示“受限设置”或不允许开启，到 设置 → 应用和服务 → 应用管理 → 打字记录 → 右上角菜单 → **允许受限设置**，再回来开启。
4. 在系统列表里找到 **打字记录**（可能在“已下载的服务”里）→ 开启 → 允许。
5. 回到应用，状态应显示“已开启 ✓”。

建议再到 设置 → 应用和服务 → 应用启动管理，把「打字记录」设为手动管理并允许后台活动，减少被系统清理的概率。

## 使用

- 正常用华为输入法打字，停顿约 1 秒后自动落一条记录。
- **查看今天的记录**：主界面直接显示时间、应用名和文字。
- **导出全部记录**：打包成 `typing-log.zip`（内含每天一个 `YYYY-MM-DD.jsonl`），通过系统分享保存到文件、发给自己或传到电脑。
- **清空全部记录**：删除本机日志，不可恢复。
- **记录开关**：临时停用，不影响无障碍授权。

JSONL 每行一条：

```json
{"ts":1730000000000,"pkg":"com.tencent.mm","app":"微信","text":"晚上七点老地方见"}
```

## 隐私

- 应用没有 `INTERNET` 权限，代码里也没有任何上传逻辑。
- 记录保存在 `/data/data/dev.local.typinglog/files/logs/`，卸载应用即清除。
- 只适合记录**你自己的**输入；如果别人也用这台手机，请先告知。

## 常见问题

**开了无障碍但没记录？**
先确认状态栏显示“已开启 ✓”，再在微信输入框打几个字等 1 秒。部分 App 的自绘输入框不会发文本变化事件，换个 App 试试。

**华为把服务关了？**
系统清理或重启后，无障碍服务一般会自动恢复；如果没恢复，重新进一次应用看状态，必要时重新打开无障碍开关。

**想要更完整的记录（含拼音、候选、删除）？**
那只能在输入法进程里做，需要 root + LSPosed 钩 `BaseInputConnection#commitText`，或者换一个自己改过的输入法（如同文输入法 Trime）。Pura 70 没有官方解锁 BL 的途径，root 这条路基本走不通。
