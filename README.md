# ❤️ 心率监测中转站 (Heart Rate Relay) v3.1 (云安全版)

一个基于 **Google Material 3 (Material You)** 设计语言的跨端实时心率监测系统。该项目支持通过安卓手机 App 采集蓝牙手表/手环（如华为、荣耀等）的心率广播，通过局域网/互联网双向通信中转到电脑端，并在 **Web 网页控制台**、**OBS 直播悬浮窗** 以及 **Windows 游戏置顶胶囊悬浮窗** 中进行多场景实时展示与历史统计。

---

## 🎯 系统架构与流程
```
  [ ⌚ 华为/荣耀等手表 ]
          │
      (BLE 蓝牙广播 0x180D)
          ▼
  [ 📱 安卓中转手机端 (AndroidApp) ]
          │
      (WebSocket + UDP 自动发现 / API Token 校验)
          ▼
  [ 💻 Windows/Linux 服务端 (Server) ]
          ├─► [ 🌐 浏览器网页控制台 (Web Dashboard) ] -> 历史记录 & 图表 (支持登录与降采样)
          ├─► [ 🎮 OBS 直播悬浮图层 (OBS Browser Source) ] -> 透明跳动心率
          ├─► [ 🖥️ Windows 置顶胶囊悬浮窗 (WindowsApp) ] -> 游戏置顶 & 鼠标穿透
          └─► [ 🚨 多渠道警报推送 (Telegram/飞书/钉钉/PushPlus) ] -> 极限心率推送
```

---

## ✨ v3.1 (云安全版) 新增特性

*   **🔒 安全认证机制**：引入 API Token，仅允许持有合规令牌的客户端（如手机、悬浮窗）连接中转，防止局域网/公网接口数据泄露。
*   **🔑 PBKDF2 密码哈希**：控制台登录密码使用行业标准 PBKDF2 算法进行盐值哈希加密，杜绝明文密码泄露隐患。
*   **🛡️ IP 暴力破解锁定**：针对网页后台登录接口，实现内存防爆破机制，同一 IP 登录失败 5 次自动锁定 60 秒，10 次以上锁定 600 秒。
*   **☁️ 云端部署模式 (Cloud Mode)**：支持通过配置文件轻松切换为云主机模式。开启后服务仅绑定 `127.0.0.1`（推荐配合 Nginx/Caddy 进行反向代理与 HTTPS SSL 加密），并自动停用 UDP 局域网明文广播。
*   **📈 数据降采样与优化**：历史数据查询支持服务端自动等比例抽稀（最大保留约 1000 个高代表性点），保持前端 Chart.js 历史曲线渲染极其轻量和流畅。
*   **🚨 极限心率预警推送**：内置 PushPlus (微信)、Telegram Bot、钉钉机器人、飞书机器人等四种主流即时通讯工具推送通道，支持用户自定义安全心率阈值与推送冷却（Cooldown）机制。

---

## 📁 项目目录结构
项目业务模块划分清晰：
```
shouhuan/ (项目根目录)
├── README.md               # 项目核心使用与开发说明文档
├── upgrade_keywords.md     # 后续 AI 升级与改造的技术关键词总结
│
├── AndroidApp/             # 📱 安卓手机端中转 App (Kotlin + Jetpack Compose)
│   ├── app/src/main/       # 安卓核心源码目录
│   │   └── java/com/hrrelay/app/
│   │       ├── MainActivity.kt   # App 界面 (Material 3 卡片布局 + 浸入式系统栏)
│   │       ├── BleManager.kt     # BLE 蓝牙设备搜索与心率广播解析 (0x180D)
│   │       ├── PcConnector.kt    # UDP 局域网广播发现与 WebSocket 电脑端通信 (带 Token)
│   │       └── KeepAliveService.kt# 动态前台通知保活服务，防系统后台休眠杀进程
│   └── build.gradle.kts    # Gradle 构建配置 (统一使用本地缓存 BOM 依赖)
│
├── WindowsApp/             # 🖥️ Windows 原生置顶悬浮窗 (C# WPF .NET 4.5)
│   ├── HeartRateOverlay.exe# 编译好的 Release 运行程序（双击直接运行）
│   ├── config.json         # 悬浮窗自动生成的本地配置文件 (IP/Token/主题等)
│   ├── MainWindow.xaml     # M3 胶囊边框外观 (CornerRadius=26 完美药丸形)
│   ├── MainWindow.xaml.cs  # 置顶/拖拽逻辑，Win32 API 鼠标穿透，M3 主题配色，WS 自动重连
│   ├── SettingsWindow.xaml # 设置窗体 (Outlined 文本框，适配 DPI 缩放防止按钮截断)
│   └── HeartRateOverlay.csproj # WPF 项目工程构建配置文件
│
└── Server/                 # 💻 服务端与 Web 控制台网页 (Python + aiohttp)
    ├── main.py             # 核心入口文件，管理后台 UDP 广播与 aiohttp 应用生命周期
    ├── config.py           # 安全密码 PBKDF2 哈希校验与本地 IP/配置管理
    ├── database.py         # SQLite 数据库助手 (支持实时写入、按范围降采样读取、HRV 字段扩展)
    ├── routes.py           # 路由分发器，处理 WebAPI 请求与 WebSocket 长连接中继
    ├── hrv.py              # HRV (RMSSD/SDNN) 疲劳度与压力分析算法核心
    ├── notifier.py         # 极限预警通知推送管理 (Telegram/微信/钉钉/飞书)
    ├── ble_monitor.py      # 本地 BLE 蓝牙特征值嗅探与解析模块
    ├── app.py              # 兼容性历史包装入口文件
    ├── requirements.txt    # 服务端 Python 依赖列表
    ├── heartrate.db        # 数据库 (本地 SQLite 存储，记录历史心率与 HRV)
    ├── server_config.json  # 服务端自动生成的 API 安全令牌 (API Token)、哈希密码及配置
    └── static/             # 🌐 Web 前端静态资源
        ├── index.html      # 控制台页面 (搭载 Google Fonts Outfit, 模块化 M3 栅格，局域网自适应指引)
        ├── style.css       # 响应式样式表 (M3 动态开关切换、自适应序号计数器)
        ├── app.js          # 与服务端 WebSocket 长连接，防暴力登录，自适应 URL 构造，历史曲线绘制
        └── login.html      # 磨砂玻璃态登录页面
```

---

## 🎨 Material 3 重构规范 (Google 风格)
本项目各端均已深度适配谷歌 **Material Design 3** 规范：
*   **Web 端**：采用 `Outfit` 与 `Noto Sans SC` 优雅字体，配以大圆角卡片、流光背景粒子、发光开关控件与谷歌 Material Symbols 矢量图标。
*   **WPF 端**：按钮为全圆角胶囊药丸状（Pill），输入框获得焦点时边框变粗并高亮深粉，支持亮暗色模式在 `#E5121214` (深色) 与 `#E5F8F9FA` (浅色) 间无缝变换。
*   **Android 端**：浸入式状态栏与导航栏根据亮暗配色自动适配，全面升级为 `ElevatedCard` 和 `OutlinedCard` 容器，加载等待采用 Compose 原生 M3 旋转指示器。

---

## 🚀 快速上手指南

### 第一步：启动 Server 服务端
1. 进入 `Server/` 目录：
   ```bash
   cd Server
   ```
2. 安装环境依赖（推荐使用 Python 3.8+）：
   ```bash
   pip install -r requirements.txt
   ```
3. 运行服务端主程序：
   ```bash
   python app.py
   ```
4. 首次启动时，控制台将**自动生成一个 8 位管理员随机密码**（已通过 PBKDF2 哈希安全写入 `server_config.json`）。同时，浏览器将自动打开 `http://localhost:8765` 跳转到登录页。
5. 控制台输出的 **API Token**（32位哈希）将用于各客户端的数据通信鉴权。

### 第二步：配置并连接手机 App
1. **安装手机客户端**：在手机上安装 `AndroidApp/` 目录下编译好的 APK，或使用 Android Studio/Gradle 编译运行：
   * 设置 `JAVA_HOME` 为您的本地 JDK 目录。
   * 在 `AndroidApp/` 目录下运行 `.\gradlew.bat assembleDebug` 生成 APK。
2. **网络环境适应性 (支持局域网与外网/云端部署)**：
   * **局域网本地模式 (默认)**：确保手机与电脑连接在**同一个 Wi-Fi**。App 会自动通过 UDP 组播发现这台电脑并建立连接。若由于网络隔离/组播被拦截导致自动连接失败，可在 App 中手动输入控制台提示的局域网 IP 地址并填入安全 Token 进行连接。
   * **云端/公网模式**：若服务器部署在公网云主机上，推荐在服务端配置文件中开启 `cloud_mode`（此时将停用 UDP 明文广播以保证安全，并限制服务仅监听 `127.0.0.1`，推荐搭配 Nginx/Caddy 配置反向代理与 HTTPS SSL）。
3. **网页端智能指引**：网页控制台能够**智能识别当前的访问来源**：
   * 局域网内访问时，提供完整的 Wi-Fi 连接和 UDP 自动发现指南。
   * 公网或域名/云端环境访问时，**自动精简步骤并屏蔽自动发现步骤**，将指南文案切换为指导复制公网 `wss://` / `ws://` 完整地址，确保安全直观。
4. **App 权限与绑定**：打开手机 App，允许蓝牙和位置/附近设备权限。点击 **“扫描手表”** 并选择您的华为/荣耀设备，心率曲线将实时双向同步传输至服务端。

### 第三步：场景扩展使用
#### 场景 A：OBS 游戏直播推流
1. 在网页端控制台的“系统设置与扩展”中，复制 **“OBS 直播推流悬浮窗”** 链接（带鉴权 Token 格式）。
2. 在 OBS 中添加一个 **“浏览器”** 来源。
3. 粘贴该链接，并将宽度设为 `200`，高度设为 `80`。
4. 此时 OBS 画面中将出现无背景、透明跳动的极简心率图层。

#### 场景 B：Windows 置顶游戏悬浮窗
1. 进入 `WindowsApp/` 目录，直接双击运行 **`HeartRateOverlay.exe`**。
2. 右键点击电脑右下角系统托盘的“红色心率图标” -> **“设置...”**。
3. 输入您的服务器 WebSocket URL（如 `ws://127.0.0.1:8765/ws`）和 **API Token**。
4. 点击保存，即可在屏幕最上层显示极简胶囊心率，悬浮窗将自动尝试连接。
5. 在托盘菜单中勾选 **“锁定 (鼠标穿透)”**，即使在激烈的全屏/窗口化游戏中也不会发生误触！
