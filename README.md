# 小米ROM直链 (Xiaomi ROM Link Browser)

一个轻量 Android 应用：浏览小米 ROM 官方直链、导出 CSV、在线提取 `payload.bin` 分区（无需下载整包）。

界面基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格 Compose 组件库）。

## 功能

- 📱 机型 / 地区 / 版本三级浏览，本地缓存 + 搜索防抖
- 🔍 搜索加强：支持按机型、代号、地区、发行名称（如 OS2.0.3.0 / Release）任意词组合搜
- ⭐ 收藏常用机型、🕘 最近浏览记录，首页置顶直达
- ⚡ 测速选源：单源或一键全测（256KB Range 实测速度），最快源自动置顶标记
- 🌙 深色模式：跟随系统 / 浅色 / 深色，在「设置」内切换
- 🧭 底栏导航：首页 / 设置 两页直达，可切换悬浮胶囊 / 贴底标签两种样式（致敬 HyperCeiler）；设置页顶部为熔岩灯式流体光效（致敬澎湃的流动光效）
- 📂 提取文件管理：查看 / 分享 / 删除已提取分区与 CSV；授予「所有文件访问」后，
  提取的分区与 CSV 直接保存到公共 `/sdcard/Download`（文件管理器可见），旧文件可一键搬移
- 🔗 ROM 详情：全量直链展示，一键导出 CSV（UTF-8 BOM，Excel 直接打开不乱码）
- 📦 在线提取 `payload.bin` 分区：HTTP Range 分段读取，只下载需要的分区数据
  - 多下载源：探测/提取阶段自动切换到下一个可用 CDN 链接，也可手动选择下载源
  - 逐分区「重试」与「重试未完成」，随时停止
  - 每个操作与整个分区双重 SHA-256 校验，校验失败保留 `.part` 断点文件
- 🔍 检查更新：启动时静默比对 GitHub Releases 最新版，「关于」内也可手动检查
- ℹ️ 关于：数据来源、开源组件与参考项目致谢

## 构建

环境要求：

- JDK 17
- Gradle 9.3.1（仓库未附带 wrapper）
- Android SDK（`compileSdk 37`，`buildTools 37.0.0`）

```bash
# local.properties 指向你的 SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 可选：配置 release 签名（不配置则产出未签名 release 包）
cat > keystore.properties <<'EOF'
storeFile=/path/to/your.keystore
storePassword=...
keyAlias=...
keyPassword=...
EOF

gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 结构

```
app/src/main/kotlin/com/diankuang/xiaomirom/
  App.kt           # 全部界面（Miuix Compose）
  MainActivity.kt
  Exporter.kt      # CSV 导出 / 分享
  Utils.kt         # 格式化、复制到剪贴板、打开文件
app/src/main/java/com/diankuang/xiaomirom/
  Http.java        # 带重试的 HTTP 客户端（Range 分段读取）
  Parser.java      # 页面解析（Jsoup）
  Payload.java     # payload.bin 元数据解析与分区提取（COW 增量、多算法解压、SHA-256）
  FileProviderLite.java
```

## 依赖

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [Miuix 0.9.4](https://github.com/compose-miuix-ui/miuix) | HyperOS 风格 UI | Apache-2.0 |
| [Jsoup 1.17.2](https://jsoup.org) | HTML 解析 | MIT |
| [XZ for Java 1.10](https://tukaani.org/xz/java.html) | LZMA2 解压 | Public Domain |
| [Apache Commons Compress 1.27.1](https://commons.apache.org/compress) | Brotli/Bzip2 解压 | Apache-2.0 |
| [Apache Commons IO 2.16.1](https://commons.apache.org/io) | Compress 运行时依赖 | Apache-2.0 |

## 参考与致谢

- [Payload Dumper Compose](https://github.com/rcmiku/Payload-Dumper-Compose) — 在线提取 payload.bin 分区的设计参考（本应用为独立实现）
- [payload-dumper-go](https://github.com/ssut/payload-dumper-go) — payload.bin 提取格式的先行开源实现

## 权限说明

- `INTERNET`：抓取 ROM 页面与 CDN 分段下载。
- `MANAGE_EXTERNAL_STORAGE`（Android 11+，**可选**）：仅用于把提取的分区 / CSV 直接写入公共
  `Download` 目录，方便文件管理器查看。不授权也能正常用，文件会存到应用私有目录，
  App 内的「提取文件」页依然可以查看、分享、删除。
- 应用不上传、不收集任何个人数据。

## 声明

本项目免费分享，**请勿用于售卖**。ROM 数据版权归小米及各 ROM 版权方所有；本工具仅提供链接浏览与官方 CDN 分段下载能力。

## 许可

Apache License 2.0，详见 [LICENSE](LICENSE)。
