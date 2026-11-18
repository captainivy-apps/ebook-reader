# ebook-reader

电子书阅读器安卓应用，原生开发，专注 EPUB 阅读与墨水屏体验优化。

- 包名：`com.captainivy.ebookreader`
- 最低支持 Android 5.0（API 21），目标 Android 14（API 34）
- 当前版本：`0.0.x`（每次 release 构建自动递增 patch）

## 功能

### 书库

- 通过系统文件选择器导入 EPUB，自动解析书名、作者、封面、目录与字数
- 列表 / 封面网格两种展示模式，网格列数随屏幕宽度自适应
- 按最近阅读时间、导入时间排序，支持正序 / 倒序
- 无限滚动分页加载
- 左滑删除、批量选择删除，删除前二次确认
- 点击书籍从上次阅读位置继续阅读

### 阅读器

- 自定义分页排版，支持章节预排版与 LRU 缓存，翻页流畅
- 点击阅读区左侧 / 右侧翻页，中间区域呼出顶部导航栏与底部功能栏
- 左手持有模式，可翻转左右翻页区域
- 支持实体按键 / 外接键盘翻页，按键可自定义（可设置多个，可清空）
- 树状目录展示，支持章节内锚点精确跳转
- 屏幕亮度调节
- 排版设置：字体、字号、行间距、左右边距、首行缩进、两端对齐
- 书籍信息查看（书名、作者、格式、大小、章节数、路径）
- 阅读区顶部显示时间与电量，底部显示章节与阅读进度
- 脚注 / 尾注点击弹窗查看
- 自动保存阅读位置与阅读时长，进程被回收后仍可恢复进度

### 墨水屏优化

- 启动时自动探测厂商 EPD 刷新接口：Onyx / 高通、Rockchip（Boyue/Likebook）、NTX、Hisense 等，普通设备自动降级为常规刷新
- 翻页快刷 + 按 N 页自动全屏清刷，减少残影
- 阅读器内可手动「全刷」
- 墨水屏设备使用纯白正文底色，提升对比度

### 朗读（TTS）

- 支持系统 TTS 与 sherpa-onnx 离线模型
- 内置多款中英文离线模型（VITS / Kokoro），可在设置中下载、切换、删除
- 模型下载支持 GitHub 原始地址与国内加速镜像，失败可切换源重试
- 朗读时自动跟随内容翻页，支持暂停 / 继续 / 上一句 / 下一句 / 语速调节
- 熄屏、锁屏或切换到后台后继续朗读（前台服务 + 唤醒锁保活）
- 通知栏与锁屏媒体控制：播放 / 暂停、上一句、下一句、停止，支持蓝牙耳机按键
- 可选「朗读时禁用手动翻页」：朗读期间触摸与按键翻页暂时失效
- 音频焦点丢失时自动暂停

### 字体

- 内置字体目录：思源宋体 / 思源黑体 / 霞鹜文楷系列等，提供国内可用下载源
- 支持从本地文件导入字体、通过 URL 下载字体
- 支持 TTF / OTF / TTC / OTC

### 设置与统计

- 局域网 HTTP 上传：开启后显示访问地址与端口（默认 8080），电脑浏览器上传 EPUB 自动入库；以前台服务运行
- 阅读统计：累计时长 / 字数 / 天数 / 书籍数、最近 7 天明细、阅读时长 Top 5、清空统计
- TTS 引擎与模型管理
- 墨水屏刷新间隔、键盘翻页按键、左手模式等
- 显示版本号与构建时间

## 技术栈

| 项目 | 说明 |
| --- | --- |
| 语言 | Kotlin 1.9.24 |
| 构建 | Gradle 8.7 + Android Gradle Plugin 8.5.2 |
| UI | 原生 Android View + ViewBinding（未使用 Compose） |
| 数据库 | SQLite（`SQLiteOpenHelper` 手写 DAO） |
| EPUB | XMLPullParser + Jsoup + commons-compress |
| 离线朗读 | sherpa-onnx（VITS / Kokoro） |
| 异步 | Kotlin Coroutines |
| JDK | Java 17 |

主要依赖：`androidx.appcompat`、`androidx.recyclerview`、`androidx.constraintlayout`、`material`、`jsoup`、`sherpa-onnx`、`commons-compress`。

## 构建

环境要求：

- JDK 17
- Android SDK Platform 34
- 使用仓库自带的 Gradle Wrapper 即可，无需单独安装 Gradle

```bash
# 调试包
./gradlew assembleDebug

# 发布包
./gradlew assembleRelease

# 单元测试
./gradlew testDebugUnitTest
```

### 版本号

版本号保存在根目录 `version.properties`（`major.minor.patch`）。执行 release / build / assemble / bundle 构建时，patch 自动 +1 并回写文件；`versionName` 与 `versionCode` 由此生成。调试构建不改变版本号。

APK 输出目录：`app/build/outputs/apk/`；发布包产物位于 `app/release/`。

## 项目结构

```text
app/src/main/java/com/captainivy/ebookreader/
├── App.kt                     # Application，初始化数据库、仓库、设置
├── data/
│   ├── BookStorage.kt         # 应用内部文件目录（书籍/封面/字体/TTS）
│   ├── FontManager.kt         # 字体目录、下载、导入
│   ├── Prefs.kt               # SharedPreferences 全局设置
│   ├── db/                    # SQLiteOpenHelper、DAO、表结构常量
│   ├── epub/                  # EPUB 解析、目录解析、章节内容与脚注解析
│   ├── importer/              # EPUB 导入与入库
│   ├── migration/             # 阅读进度字符基准迁移
│   ├── model/                 # Book / Chapter / TocItem / 统计模型
│   ├── repo/                  # BookRepository 数据访问入口
│   └── tts/                   # TTS 控制器、系统/离线引擎、模型管理与分段
├── net/                       # 局域网 HTTP 上传前台服务
├── ui/
│   ├── library/               # 书库页、书籍列表、左滑删除
│   ├── reader/                # 阅读器、自定义阅读视图、分页、墨水屏刷新
│   └── settings/              # 设置页、统计页
└── util/                      # 日期、按键名称、网络、位图工具
```

## 数据存储

- 书籍、封面、字体、TTS 模型均存于应用内部存储（`filesDir` 下的 `books/`、`covers/`、`fonts/`、`tts/`）
- 数据库表：`books`、`chapters`、`toc`、`reading_stats`
- 阅读进度以「字符偏移」为基准，记录当前章节、总偏移、历史最大偏移与完成状态
- 阅读统计按天记录阅读时长与新读字数

## 权限

- `INTERNET` / `ACCESS_NETWORK_STATE`：字体与 TTS 模型下载，以及访问局域网 HTTP 服务（如从局域网 URL 导入字体）
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `WAKE_LOCK`：局域网 HTTP 上传服务
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `WAKE_LOCK`：熄屏与后台朗读保活
- `POST_NOTIFICATIONS`：Android 13+ 上传服务与朗读播放通知

## 说明

- 第一期仅支持 EPUB 格式
- 所有功能离线可用（除字体、TTS 模型下载与局域网传书）
- 允许明文 HTTP（`networkSecurityConfig` 中 `cleartextTrafficPermitted`），用于从局域网 HTTP 服务导入字体；targetSdk 34 下本地网络随 `INTERNET` 隐式授予，无需 `ACCESS_LOCAL_NETWORK`（该权限仅 targetSdk ≥ 37 强制）
- 应用面向墨水屏低配设备优化，翻页与刷新策略优先保证流畅、低残影

## License

[GNU Affero General Public License v3.0](LICENSE)
