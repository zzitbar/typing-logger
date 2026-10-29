# 数据记录（TypingLog）

一个只在你自己的手机上跑的输入记录器：用无障碍服务记录输入框里**上屏**的文字，按天存本地，并可选实时推送到飞书群机器人。

- 不需要 root，不需要电脑。
- 进界面要密码（默认 `ZHHL@91xxkj`，可改），记录本身在后台继续，不受锁屏影响。
- 本地默认不联网；只有你开启“飞书推送”后，新记录才会发到你填的 Webhook。
- 密码框由系统屏蔽，读不到也不会记录。

## 功能

| 功能 | 说明 |
|---|---|
| 记录上屏文字 | 微信、短信、备忘录、浏览器等大多数输入框，带时间与应用名 |
| 密码锁 | 进入界面需输入密码；退到后台超过 60 秒再回来需重新输入 |
| 飞书推送 | 每条新记录实时推到飞书群自定义机器人，支持签名校验 |
| 本地导出 | 打包成 zip，通过系统分享保存或传走 |
| 暂停/清空 | 记录开关随时暂停；一键清空本机记录 |

## 原理与边界

输入法把文字提交给输入框时，输入框发出 `TYPE_VIEW_TEXT_CHANGED`，事件里带变化后的完整文本。本应用监听该事件，按“应用 + 窗口”聚合，停顿 800ms 或输入框被清空（发送/切走）时落一条记录；开启推送时同一条记录再发往飞书。

| 能记录 | 记录不到 |
|---|---|
| 最终上屏的汉字/英文、时间、所属应用 | 拼音串、候选词、选了第几个候选 |
| 发送瞬间的整段文字 | 被删掉的中间过程 |
| 微信、短信、备忘录、浏览器等大多数输入框 | 密码框、银行类安全键盘、游戏/Canvas 自绘界面 |

个别输入框会在打拼音的过程中把 `nihao` 这种临时串也报上来；两次停顿超过 800ms 时可能多一条拼音记录，属正常现象。

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
        │   ├── MainActivity.kt      界面：密码锁、开关、查看、导出、飞书配置
        │   ├── TypingLogService.kt  无障碍服务：捕获并聚合上屏文字
        │   ├── LogStore.kt          本地 JSONL 存储与 zip 导出
        │   ├── FeishuPusher.kt      飞书 Webhook 推送（签名、限速、队列）
        │   ├── Passwords.kt         密码校验（SHA-256）
        │   ├── Keys.kt              SharedPreferences 键
        │   └── Record.kt            数据结构
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml, themes.xml
            └── xml/accessibility_service_config.xml, file_paths.xml
```

## 构建

环境：Android Studio Hedgehog (2023.1.1) 或更新，JDK 17，Android SDK 34。

1. Android Studio → **Open**，选择 `typing-logger` 目录。
2. 等 Gradle sync 完成（首次下载 Gradle 8.2、AGP 8.2.2、Kotlin 1.9.22、androidx.core）。
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**，产物在 `app/build/outputs/apk/debug/app-debug.apk`。

命令行（需本机 Gradle 8.2+ 与 SDK 34）：

```bash
cd typing-logger
gradle assembleDebug
```

## 安装与授权

1. **先退出纯净模式**：设置 → 系统和更新 → 纯净模式 → 退出（需要华为账号密码），否则会拦截非应用市场安装包。
2. 安装 `app-debug.apk`；提示“未经检测”时选继续安装。
3. 打开「数据记录」，输入默认密码 `ZHHL@91xxkj`。
4. 点 **去开启无障碍服务** → 在“已下载的服务”里找到「数据记录」→ 开启 → 允许。Android 13 及以上若提示“受限设置”，到 应用信息 → 右上角 → 允许受限设置。
5. 回到应用，状态应显示“已开启 ✓”。

建议把「数据记录」加入电池优化白名单（设置 → 应用和服务 → 应用启动管理 → 手动管理，允许后台活动）。

## 飞书推送配置

1. 在飞书里打开目标群 → 群设置 → **群机器人** → 添加机器人 → **自定义机器人**。
2. 起个名字（如“数据记录”），复制它给出的 **Webhook 地址**（形如 `https://open.feishu.cn/open-apis/bot/v2/hook/xxxx`）。
3. 安全设置里如果选了 **签名校验**，把 Secret 一并复制；选“自定义关键词”时，关键词填 `数据记录`（推送内容里带这个词）。
4. 回到 App：粘贴 Webhook、Secret → **保存** → 打开“检测到新记录就推到飞书” → 点 **测试推送** 验证。

推送内容示例：

```
【数据记录】10-29 09:12:33 · 微信
晚上七点老地方见
```

发送是后台串行、限速 1 秒 1 条，队列最多 200 条，满了丢最旧的，不会卡输入。飞书机器人本身有频率限制（约 100 条/分钟），高频输入时消息会排队陆续到达，而不是丢字。

## 密码

- 默认密码：`ZHHL@91xxkj`
- 修改：主界面最下方 **修改密码**，需要原密码，新密码至少 4 位。
- 只保存 SHA-256，不存明文。
- 锁只保护界面；后台记录不受影响。忘记密码只能清除应用数据（会丢记录）。

## 隐私

- 应用现在声明了 `INTERNET` 权限，唯一用途是向**你自己填的**飞书 Webhook 发消息；不开启推送就不联网。
- 记录保存在 `/data/data/dev.local.typinglog/files/logs/`，卸载应用即清除。
- 推送会把文字发到飞书服务器，请确认群成员范围可接受。
- 只适合记录**你自己的**输入；如果别人也用这台手机，请先告知。

## 常见问题

**测试推送失败？**
先看主界面“推送状态”里的错误：`HTTP 4xx` 多半是 Webhook 抄错或机器人被移除；返回 `sign match fail` 是 Secret 不对；返回关键词不匹配，就在群里把关键词改成 `数据记录`。

**开了无障碍但没记录？**
确认状态显示“已开启 ✓”，在微信里打几个字等 1 秒。部分 App 自绘输入框不发文本变化事件，换个 App 试试。

**想要更完整的记录（含拼音、候选、删除）？**
只能在输入法进程里做，需要 root + LSPosed 钩 `BaseInputConnection#commitText`，或换一个自己改过的输入法（如同文输入法 Trime）。Pura 70 没有官方解锁 BL 的途径，root 这条路基本走不通。
