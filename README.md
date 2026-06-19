# ❤️ 心率监测中转站 (Heart Rate Relay)

一个基于 **Google Material 3 (Material You)** 设计语言的跨端实时心率监测系统。该项目支持通过安卓手机 App 采集蓝牙手表/手环（如华为、荣耀等）的心率与 R-R 间期数据，通过局域网/互联网双向通信中转到电脑端，并在 **Web 网页控制台**、**OBS 直播悬浮窗** 以及 **Windows 游戏置顶胶囊悬浮窗** 中进行多场景实时展示、智能 HRV 身体疲劳度评估与历史统计。

---

## 🎯 系统架构与流程
```
  [ ⌚ 蓝牙手表/手环 ]
          │
      (BLE 蓝牙广播 0x180D)
          ▼
  [ 📱 安卓中转手机端 (AndroidApp) ]
          │
      (WebSocket + UDP 自动发现 / API Token 校验)
          ▼
  [ 💻 Windows/Linux 服务端 (Server) ]
          ├─► [ 🌐 浏览器网页控制台 (Web Dashboard) ] -> 实时心率/HRV、历史记录与自适应指南
          ├─► [ 🎮 OBS 直播悬浮图层 (OBS Browser Source) ] -> 透明跳动心率
          ├─► [ 🖥️ Windows 置顶胶囊悬浮窗 (WindowsApp) ] -> 游戏置顶 & 鼠标穿透
          └─► [ 🚨 多渠道警报推送 (Telegram/飞书/钉钉/PushPlus) ] -> 极限心率推送
```

---

## ✨ 核心特性

*   **📈 实时 HRV 与身体疲劳度评估**：支持从蓝牙手表提取 R-R 间期数据，通过 RMSSD 时域算法滚动计算身体疲劳度与交感神经压力指数，并在 Web 端动态反馈。
*   **🔒 全方位安全防护体系**：引入 API Token 握手校验，防止局域网/公网接口数据泄露；管理员登录密码采用 PBKDF2 盐值哈希加密，登录接口内置防撞库自动锁定机制。
*   **☁️ 云端自适应部署 (Cloud Mode)**：支持一键切换为云主机模式。网页端控制台能智能识别您的网络访问环境，自动精简或隐藏局域网组播步骤，提供安全加密的反向代理与 WSS 连接指引。
*   **📉 数据降采样与优化**：历史数据查询支持服务端自动等比例抽稀，保持前端 Chart.js 历史曲线渲染极其轻量和流畅。
*   **🚨 极限心率预警推送**：内置 PushPlus (微信)、Telegram Bot、钉钉机器人、飞书机器人等四种主流预警通道，支持自定义安全阈值与推送冷却（Cooldown）机制。

---

## 📁 项目目录结构
```
shouhuan/ (项目根目录)
├── AndroidApp/             # 📱 安卓手机端中转 App (Kotlin + Jetpack Compose)
├── WindowsApp/             # 🖥️ Windows 原生置顶悬浮窗 (C# WPF .NET 4.5)
├── Server/                 # 💻 服务端与 Web 控制台网页 (Python + aiohttp)
│   ├── static/             # 🌐 Web 前端静态资源 (index.html, app.js, style.css)
│   ├── Dockerfile          # 后端 Docker 构建镜像文件
│   └── main.py             # 服务端核心主入口
├── docker-compose.yml      # Docker 容器化编排配置文件 (Server + Caddy 网关)
├── Caddyfile               # Caddy 自动化 SSL 证书与反向代理配置文件
└── README.md               # 项目使用说明文档
```

---

## 🎨 Material 3 重构规范 (Google 风格)
本项目各端均已深度适配谷歌 **Material Design 3** 规范：
*   **Web 端**：采用 `Outfit` 与 `YaHei UI` 优雅字体，配以大圆角卡片、流光背景粒子、发光开关控件与谷歌 Material Symbols 矢量图标。
*   **WPF 端**：按钮为全圆角胶囊药丸状（Pill），输入框获得焦点时边框变粗并高亮深粉，支持亮暗色模式在 `#E5121214` (深色) 与 `#E5F8F9FA` (浅色) 间无缝变换。
*   **Android 端**：浸入式状态栏与导航栏根据亮暗配置自动适配，全面升级为 `ElevatedCard` 和 `OutlinedCard` 容器，加载等待采用 Compose 原生 M3 旋转指示器。

---

## 🚀 快速上手指南

### 第一步：启动 Server 服务端

#### 方法 A：使用 Docker Compose 一键启动 (推荐，适合云服务器部署)
1. **修改域名 (可选)**：若您有公网域名，请编辑根目录下的 `Caddyfile`，将首行替换为您的域名；若只通过 IP 访问，则无需修改。
2. **一键运行**：在项目根目录下，直接执行：
   ```bash
   docker compose up -d --build
   ```
3. **获取自动生成的密钥/密码**：容器首次运行会自动在根目录创建 `data/` 目录，运行以下命令查看系统随机生成的密码 and Token：
   ```bash
   cat data/server_config.json
   ```

#### 方法 B：本地 Python 源码运行 (适合本机开发调试)
1. 进入 `Server/` 目录：
   ```bash
   cd Server
   ```
2. 安装环境依赖（推荐使用 Python 3.8 - 3.12）：
   ```bash
   pip install -r requirements.txt
   ```
3. 运行服务端主程序：
   ```bash
   python main.py
   ```
4. 首次启动时，控制台将**自动生成一个 8 位管理员随机密码**（安全保存至 `server_config.json`）。同时，浏览器将自动打开控制台页面。

---

### 第二步：配置并连接手机 App
1. **安装手机客户端**：在手机上安装 `AndroidApp/` 目录下编译好的 APK，或使用 Android Studio 编译运行。
2. **网络连接**：
   * **局域网模式**：确保手机与电脑在**同一 Wi-Fi**。App 会通过 UDP 自动扫描并建立连接；若组播被拦截，可在 App 中手动输入电脑 IP 和安全 Token 进行连接。
   * **云端/公网模式**：在手机 App 的服务器地址中手动输入公网 HTTPS 代理后的 `wss://` / `ws://` 连接地址，并输入您的安全 Token 即可。
3. **App 权限与绑定**：打开手机 App，允许蓝牙和位置/附近设备权限。点击 **“扫描手表”** 并选择您的蓝牙设备，心率曲线将实时双向同步传输至服务端。

---

### 第三步：场景扩展使用
#### 场景 A：OBS 游戏直播推流
1. 在网页端控制台的“系统设置与扩展”中，复制 **“OBS 直播推流悬浮窗”** 链接。
2. 在 OBS 中添加一个 **“浏览器”** 来源。
3. 粘贴该链接，并将宽度设为 `200`，高度设为 `80`。
4. 此时 OBS 画面中将出现无背景、透明跳动的极简心率图层。

#### 场景 B：Windows 置顶游戏悬浮窗
1. 进入 `WindowsApp/` 目录，直接双击运行 **`HeartRateOverlay.exe`**。
2. 右键点击电脑右下角系统托盘的“红色心率图标” -> **“设置...”**。
3. 输入您的服务器 WebSocket URL（如 `ws://127.0.0.1:8765/ws`）和 **API Token**。
4. 点击保存，即可在屏幕最上层显示极简胶囊心率，悬浮窗将自动尝试连接。
5. 在托盘菜单中勾选 **“锁定 (鼠标穿透)”**，即使在全屏游戏中也不会发生误触！
