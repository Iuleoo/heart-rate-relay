# 📱 心率中转助手 - Heart Rate Relay

Android 手机端心率中转 App。连接华为手表蓝牙，通过 Wi-Fi 局域网将心率数据转发给 Windows 电脑。

## 🚀 快速开始

### 1. 用 Android Studio 打开本项目
- 用 Android Studio 打开 `android/` 文件夹
- 等待 Gradle 同步完成

### 2. 构建并安装到手机
- 连接手机 USB 调试
- 点击 Run 安装到手机

### 3. 使用流程
1. 打开 App，授予蓝牙和位置权限
2. App 会自动在局域网中搜寻电脑端
3. 也可以手动输入电脑 IP 地址连接
4. 点击「扫描手表」连接华为手表
5. 连接成功后心率数据自动转发给电脑

## 📋 系统要求
- Android 8.0 (API 26) 或更高版本
- 支持 BLE 的手机
- 手机和电脑需连接同一 Wi-Fi 网络

## 🔧 技术栈
- Kotlin + Jetpack Compose (Material3)
- BLE: Android 原生 BluetoothGatt API
- 网络: OkHttp WebSocket
- 发现: UDP 广播监听
