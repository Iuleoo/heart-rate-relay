# ⌚ 心率监测器项目升级与改造关键词总结

为了方便您将此项目提供给其他 AI 进行后续的升级与改造，以下整理了该项目的核心架构、技术栈、现有工作流以及推荐的升级方向和关键词。

---

## 🔍 1. 现有项目概况与核心技术栈

目前的项目是一个**跨端分布式实时心率监测系统 (v3.2 云端自适应安全版)**，支持多设备协同工作。

### 📱 安卓中转端 ([AndroidApp](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/AndroidApp/))
- **定位**: 蓝牙心率与 R-R 间期数据采集，通过 WebSocket 中转传输
- **技术栈**: Kotlin + Jetpack Compose (Material 3) + BLE GATT (0x180D/0x2A37 包含心率与 RR Interval 原始数据) + OkHttp WebSocket
- **关键文件**: 
  - [MainActivity.kt](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/AndroidApp/app/src/main/java/com/hrrelay/app/MainActivity.kt) — App 交互界面与局域网重连/手动连接配置/自动扫描逻辑
  - [BleManager.kt](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/AndroidApp/app/src/main/java/com/hrrelay/app/BleManager.kt) — BLE 蓝牙广播搜索与心率特征值解析 (支持解析多字节 R-R Interval 数组)
  - [PcConnector.kt](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/AndroidApp/app/src/main/java/com/hrrelay/app/PcConnector.kt) — UDP 自动广播扫描与带 Token 校验的 WebSocket 客户端中继模块
  - [KeepAliveService.kt](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/AndroidApp/app/src/main/java/com/hrrelay/app/KeepAliveService.kt) — 前台通知服务与高精度 Wi-Fi 锁，防止手机后台休眠断连

### 💻 Python 服务端 ([Server](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/))
- **定位**: 模块化数据中继、安全网关、HRV 计算引擎、SQLite 持久化与 Web 控制台网页
- **技术栈**: Python + aiohttp (WebSocket) + SQLite3 + PBKDF2 加密 + 实时 HRV (RMSSD) 算法 + 警报推送
- **关键文件**: 
  - [main.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/main.py) — 核心服务入口，处理应用生命周期与 UDP 局域网组播广播
  - [config.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/config.py) — PBKDF2 密码哈希、会话管理以及服务端本地/云模式参数读取
  - [database.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/database.py) — SQLite 持久化管理（支持降采样数据稀释，带有 `hrv_rmssd`, `stress_index`, `fatigue_level` 列）
  - [routes.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/routes.py) — REST API 路由控制器与安全 WebSocket 连接中继（支持 IP 防暴力破解锁）
  - [hrv.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/hrv.py) — HRV 计算核心，基于 R-R 间期滚动窗口计算 RMSSD、自主神经压力指数 (Stress Index) 及身体疲劳度
  - [notifier.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/notifier.py) — 多渠道极限心率推送报警模块 (Telegram/飞书/钉钉/PushPlus)
  - [app.js](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/app.js) — 前端自适应环境识别、双向 WebSocket 重连、Chart.js 历史/实时双曲线渲染
  - [index.html](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/index.html) — M3 栅格化 Dashboard，集成 HRV 疲劳卡片、动态 SVG 提示与自适应步骤 CSS 计数器

### 🖥️ Windows 原生端 ([WindowsApp](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/WindowsApp/))
- **定位**: 游戏画面最顶层心率悬浮窗 (支持鼠标穿透)
- **技术栈**: C# WPF + Win32 API 鼠标穿透 + WebSocket 长连接
- **关键文件**: 
  - [MainWindow.xaml.cs](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/WindowsApp/MainWindow.xaml.cs) — 置顶/拖拽逻辑，Win32 API 鼠标穿透，托盘交互
  - [config.json](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/WindowsApp/config.json) — 桌面端本地配置文件（WebSocket 地址、Token 与主题配色）

---

## 🚀 2. 后续升级与改造方向及提示词

由于“SQLite 存储”、“HRV 疲劳评估与滚动计算”、“多渠道预警”、“WPF 悬浮窗”等核心业务已完全跑通，后续推荐向以下进阶方向升级：

### 📊 方向 A：卡氏 (Karvonen) 个性化心率区间与运动强度设定
* **目标**: 支持用户设定最大心率、静息心率与年龄，应用卡氏公式动态划分 5 大心率区间（热身、燃脂、有氧、无氧、极限），并在图表中高亮展示对应占比。
* **AI 提示关键词**:
  > “请在 [config.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/config.py) 中增加最大心率、静息心率和年龄的配置项，在 [index.html](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/index.html) 增加卡氏区间设定面板。修改 [static/app.js](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/app.js) 的 Chart.js 绘制逻辑，在背景渲染 5 大心率运动区间颜色带，并计算本次运动中各区间占比的饼图。”

### 🚨 方向 B：物联网与智能家居联动 (IoT & Smart Home)
* **目标**: 联动房间智能灯光，在极限心率或极高疲劳度时，智能灯光闪烁红色预警。
* **AI 提示关键词**:
  > “请在 [notifier.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/notifier.py) 中集成 **MQTT 客户端** 或 **Home Assistant REST API** 接口。当实时心率触发超标预警时，向 `homeassistant/light/heartrate` 发送控制包，使智能电竞灯光或房间顶灯自动变红并闪烁预警。”

### 👥 方向 C：多人/多设备竞技模式 (Multi-user Dashboard)
* **目标**: 支持电竞战队、多人直播或群组健身场景，在同一控制台展示多路心率对比。
* **AI 提示关键词**:
  > “修改 [routes.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/routes.py) 的 WebSocket 消息分发机制，支持根据客户端传入的 `device_id` 或 `user_name` 区分多路隔离的心率通道。修改 [static/index.html](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/index.html) 和 [static/app.js](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/static/app.js)，使 Dashboard 能够以多卡片或多图线形式，**同时展示多个人的实时心率、疲劳度和压力值**。”

### 📈 方向 D：高级 HRV 频域与非线性动力学分析
* **目标**: 在现有时域 RMSSD 算法基础上，计算 SDNN、LF/HF 比值或绘制 Poincaré 庞加莱散点图。
* **AI 提示关键词**:
  > “在 [hrv.py](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/hrv.py) 中增加 SDNN 指标计算，并使用 `scipy` 库对 R-R 间期序列进行功率谱密度分析，计算 LF/HF（低频/高频比值）以深入评估交感与副交感神经活性。在前端网页使用 Chart.js 的 `scatter` 类型绘制 **Poincaré (庞加莱) 散点图**。”

### ☁️ 方向 E：自动化生产环境反向代理配置 (Caddy / Nginx SSL)
* **目标**: 自动化生成配置以一键布署于外网并启用 SSL 加密（WSS）。
* **AI 提示关键词**:
  > “在 [Server/](file:///C:/Users/LiyaM/Desktop/agy/shouhuan/Server/) 目录下编写一个脚本，能自动为 Nginx 或 Caddy 生成反向代理配置文件，为当前的 Web 控制台和 WebSocket 提供自动获取 Let's Encrypt 证书的 HTTPS 与 WSS 支持，并一键完成云端生产环境的安全加固。”
