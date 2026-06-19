package com.hrrelay.app

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * 电脑端连接器
 * 负责通过 UDP 自动发现局域网中的电脑端服务，以及通过 WebSocket 发送心率数据
 */
class PcConnector {

    companion object {
        private const val TAG = "PcConnector"
        private const val UDP_PORT = 8766
        private const val UDP_PREFIX = "HR_MONITOR_SERVER:"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build()
    private var webSocket: WebSocket? = null
    private var udpJob: Job? = null
    private var connected = false
    var securityToken: String = "" // 缓存安全 Token，支持自动重连和自动发现使用

    // 自动重连控制
    private var lastWsUrl: String? = null
    private var isIntentionallyDisconnected = false
    private var reconnectJob: Job? = null

    // 回调
    var onPcDiscovered: ((String) -> Unit)? = null  // 发现电脑时回调 ws URL
    var onConnected: ((String) -> Unit)? = null     // 连接成功
    var onDisconnected: ((String) -> Unit)? = null   // 连接断开
    var onError: ((String) -> Unit)? = null          // 错误信息

    // ─── UDP 自动发现 ──────────────────────────────────────────

    fun startDiscovery() {
        udpJob?.cancel()
        udpJob = scope.launch {
            Log.i(TAG, "开始 UDP 自动发现...")
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(UDP_PORT)
                socket.broadcast = true
                socket.soTimeout = 3000  // 3 秒超时
                val buffer = ByteArray(1024)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        if (message.startsWith(UDP_PREFIX)) {
                            val wsUrl = message.removePrefix(UDP_PREFIX)
                            Log.i(TAG, "发现电脑端: $wsUrl")
                            withContext(Dispatchers.Main) {
                                onPcDiscovered?.invoke(wsUrl)
                            }
                            // 发现后自动连接
                            if (!connected) {
                                connectToPc(wsUrl)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // 超时继续监听
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "UDP 监听异常: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onError?.invoke("局域网搜寻异常: ${e.message}")
                    }
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun stopDiscovery() {
        udpJob?.cancel()
        udpJob = null
    }

    // ─── WebSocket 连接 ────────────────────────────────────────

    @Synchronized
    fun connectToPc(wsUrl: String) {
        // 取消任何待处理的重连任务
        reconnectJob?.cancel()

        if (connected) {
            Log.d(TAG, "已连接，跳过重复连接")
            return
        }

        isIntentionallyDisconnected = false
        lastWsUrl = wsUrl

        // 提取 URL 中的 token 报头，并清理 URL，以防反向代理日志泄露
        var cleanUrl = wsUrl
        var useToken = securityToken

        if (wsUrl.contains("token=")) {
            val tokenRegex = "token=([^&]+)".toRegex()
            val matchResult = tokenRegex.find(wsUrl)
            if (matchResult != null) {
                useToken = matchResult.groupValues[1]
            }
            cleanUrl = wsUrl.replace(tokenRegex, "").replace("?&", "?").replace("&&", "&")
            if (cleanUrl.endsWith("?") || cleanUrl.endsWith("&")) {
                cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
            }
        }

        Log.i(TAG, "正在连接电脑 (Header 认证): $cleanUrl")
        val requestBuilder = Request.Builder().url(cleanUrl)
        if (useToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $useToken")
        }
        val request = requestBuilder.build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "WebSocket 已连接")
                scope.launch(Dispatchers.Main) {
                    onConnected?.invoke(cleanUrl)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                this@PcConnector.webSocket = null
                Log.i(TAG, "WebSocket 已关闭: $reason")
                scope.launch(Dispatchers.Main) {
                    onDisconnected?.invoke("连接已关闭")
                }
                startReconnectLoop()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                this@PcConnector.webSocket = null
                Log.e(TAG, "WebSocket 连接失败: ${t.message}")
                scope.launch(Dispatchers.Main) {
                    onError?.invoke("连接电脑失败: ${t.message}")
                }
                startReconnectLoop()
            }
        })
    }

    private fun startReconnectLoop() {
        if (isIntentionallyDisconnected || reconnectJob?.isActive == true || connected) return

        reconnectJob = scope.launch {
            delay(5000) // 5 秒后重试
            if (isActive && !connected && !isIntentionallyDisconnected) {
                val url = lastWsUrl
                if (url != null) {
                    Log.i(TAG, "正在尝试自动重连服务器: $url")
                    connectToPc(url)
                }
            }
        }
    }

    fun disconnect() {
        isIntentionallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        connected = false
    }

    // ─── 发送心率数据 ──────────────────────────────────────────

    fun sendHeartRate(hr: Int, rrIntervals: List<Int>? = null, deviceName: String? = null) {
        if (!connected || webSocket == null) return

        val sb = java.lang.StringBuilder()
        sb.append("{")
        sb.append("\"hr\": ").append(hr)
        if (rrIntervals != null && rrIntervals.isNotEmpty()) {
            sb.append(", \"rr\": ").append(rrIntervals.toString())
        }
        if (deviceName != null) {
            sb.append(", \"device_name\": \"").append(deviceName).append("\"")
        }
        sb.append("}")

        val payload = sb.toString()
        val sent = webSocket?.send(payload) ?: false
        if (sent) {
            Log.d(TAG, "已发送心率: $hr BPM, rr: ${rrIntervals ?: "无"}")
        }
    }

    fun isConnected(): Boolean = connected

    fun destroy() {
        stopDiscovery()
        disconnect()
        scope.cancel()
    }
}
