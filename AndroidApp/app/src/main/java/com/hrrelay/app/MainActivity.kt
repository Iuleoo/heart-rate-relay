package com.hrrelay.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var pcConnector: PcConnector
    private var autoReconnectAddress: String? = null

    // 自动重连控制相关
    private var isWatchIntentionallyDisconnected = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bleReconnectRunnable: Runnable? = null

    // ─── UI 状态 ──────────────────────────────────────────────
    private val currentHr = mutableIntStateOf(0)
    private val bleStatus = mutableStateOf("未连接")
    private val pcStatus = mutableStateOf("搜寻电脑中...")
    private val pcUrl = mutableStateOf("")
    private val watchName = mutableStateOf("")
    private val isPcConnected = mutableStateOf(false)
    private val isWatchConnected = mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<Pair<BluetoothDevice, Int>>()
    private val isScanning = mutableStateOf(false)
    private val manualServerUrl = mutableStateOf("")
    private val securityToken = mutableStateOf("")

    /**
     * 通知 KeepAliveService 更新动态前台服务通知
     */
    private fun notifyServiceStateChanged() {
        KeepAliveService.updateState(
            currentHr.intValue,
            isWatchConnected.value,
            isPcConnected.value,
            bleStatus.value
        )
    }

    /**
     * 触发蓝牙手表自动重连
     */
    private fun triggerBleReconnect() {
        val sharedPref = getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
        val lastAddr = sharedPref.getString("last_watch_address", "") ?: ""
        val lastWatchName = sharedPref.getString("last_watch_name", "") ?: ""
        if (lastAddr.isEmpty() || isWatchIntentionallyDisconnected) return

        bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        bleReconnectRunnable = Runnable {
            if (!isWatchConnected.value && !isScanning.value && !isWatchIntentionallyDisconnected) {
                android.util.Log.i("MainActivity", "触发后台自动重连手环 ($lastWatchName)...")
                autoReconnectAddress = lastAddr
                isScanning.value = true
                bleStatus.value = "正在重连上次的手表: $lastWatchName..."
                notifyServiceStateChanged()
                bleManager.startScan(lastAddr)
                updateKeepAliveServiceStatus()
            }
        }
        mainHandler.postDelayed(bleReconnectRunnable!!, 5000)
    }

    // ─── 权限请求 ─────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initBle()
        } else {
            Toast.makeText(this, "需要蓝牙和位置权限才能扫描设备", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager(this)
        pcConnector = PcConnector()

        // 读取上次保存的连接配置并自动尝试连接
        val sharedPref = getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("last_server_url", "") ?: ""
        val savedToken = sharedPref.getString("last_security_token", "") ?: ""
        val legacyIp = sharedPref.getString("last_pc_ip", "") ?: ""

        if (savedUrl.isNotEmpty()) {
            manualServerUrl.value = savedUrl
            securityToken.value = savedToken
            pcConnector.securityToken = savedToken
            val wsUrl = buildWebSocketUrl(savedUrl, savedToken)
            pcConnector.connectToPc(wsUrl)
            pcStatus.value = "正在连接服务器..."
            updateKeepAliveServiceStatus()
        } else if (legacyIp.isNotEmpty()) {
            manualServerUrl.value = legacyIp
            pcConnector.securityToken = ""
            val wsUrl = buildWebSocketUrl(legacyIp, "")
            pcConnector.connectToPc(wsUrl)
            pcStatus.value = "正在重连上次电脑 $legacyIp..."
            updateKeepAliveServiceStatus()
        }

        setupBleCallbacks()
        setupPcCallbacks()

        // 请求权限
        requestPermissions()

        // 启动 UDP 自动发现
        pcConnector.startDiscovery()

        setContent {
            HeartRateRelayTheme {
                MainScreen()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            initBle()
        }
    }

    private fun buildWebSocketUrl(input: String, token: String): String {
        var targetUrl = input.trim()
        if (targetUrl.isEmpty()) return ""

        if (!targetUrl.startsWith("ws://") && !targetUrl.startsWith("wss://")) {
            targetUrl = "ws://$targetUrl"
            if (!targetUrl.contains(":") && !targetUrl.substringAfter("ws://").contains("/")) {
                targetUrl = "$targetUrl:8765"
            }
        }

        if (!targetUrl.contains("/ws/phone")) {
            val hasQuery = targetUrl.contains("?")
            val base = if (hasQuery) targetUrl.substringBefore("?") else targetUrl
            val query = if (hasQuery) targetUrl.substringAfter("?") else ""
            
            var formattedBase = base
            if (!formattedBase.endsWith("/")) {
                formattedBase += "/"
            }
            formattedBase += "ws/phone"
            
            targetUrl = if (hasQuery) "$formattedBase?$query" else formattedBase
        }

        if (token.isNotEmpty() && !targetUrl.contains("token=")) {
            targetUrl += if (targetUrl.contains("?")) "&" else "?"
            targetUrl += "token=$token"
        }

        return targetUrl
    }

    private fun initBle() {
        bleStatus.value = "蓝牙已就绪"
        
        // 自动重连上次的手表
        val sharedPref = getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
        val lastWatchAddress = sharedPref.getString("last_watch_address", "") ?: ""
        val lastWatchName = sharedPref.getString("last_watch_name", "") ?: ""
        
        if (lastWatchAddress.isNotEmpty()) {
            autoReconnectAddress = lastWatchAddress
            bleStatus.value = "正在寻找上次的手表: $lastWatchName..."
            isScanning.value = true
            notifyServiceStateChanged()
            bleManager.startScan(lastWatchAddress) // 传入设备 MAC 地址过滤，节省电能
            updateKeepAliveServiceStatus()
        }
    }

    // ─── 回调设置 ─────────────────────────────────────────────

    private fun setupBleCallbacks() {
        bleManager.onDeviceFound = { device, rssi ->
            runOnUiThread {
                // 避免重复添加
                if (discoveredDevices.none { it.first.address == device.address }) {
                    discoveredDevices.add(Pair(device, rssi))
                }
                
                // 检查是否为自动重连的目标设备
                val targetAddr = autoReconnectAddress
                if (targetAddr != null && device.address == targetAddr) {
                    android.util.Log.i("MainActivity", "发现上次连接的手表，正在自动重连...")
                    autoReconnectAddress = null
                    bleManager.connect(device)
                }
            }
        }
        bleManager.onHeartRateReceived = { hr, rr ->
            runOnUiThread {
                currentHr.intValue = hr
                notifyServiceStateChanged()
            }
            // 转发给电脑
            pcConnector.sendHeartRate(hr, rr, watchName.value.ifEmpty { null })
        }
        bleManager.onStatusChanged = { status ->
            runOnUiThread {
                bleStatus.value = status
                notifyServiceStateChanged()
                if (status == "扫描完成" || status.startsWith("⚠️") || status.startsWith("❌") || 
                    status.contains("未开启") || status.contains("已关闭") || status.contains("失败") || status.contains("无法获取")) {
                    isScanning.value = false
                    updateKeepAliveServiceStatus()
                    if (!isWatchConnected.value) {
                        triggerBleReconnect()
                    }
                }
            }
        }
        bleManager.onConnected = { name ->
            runOnUiThread {
                isWatchConnected.value = true
                watchName.value = name
                isScanning.value = false
                bleStatus.value = "✅ 已连接: $name"
                notifyServiceStateChanged()
                updateKeepAliveServiceStatus()
                bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            }
        }
        bleManager.onDisconnected = {
            runOnUiThread {
                isWatchConnected.value = false
                watchName.value = ""
                currentHr.intValue = 0
                bleStatus.value = "手表已断开"
                notifyServiceStateChanged()
                updateKeepAliveServiceStatus()
                triggerBleReconnect()
            }
        }
    }

    private fun setupPcCallbacks() {
        pcConnector.onPcDiscovered = { url ->
            pcUrl.value = url
        }
        pcConnector.onConnected = { url ->
            isPcConnected.value = true
            pcStatus.value = "✅ 已连接服务器"
            pcUrl.value = url
            notifyServiceStateChanged()
            
            // 记住这次成功连接的配置
            val serverAddress = manualServerUrl.value.trim()
            val token = securityToken.value.trim()
            if (serverAddress.isNotEmpty()) {
                getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_server_url", serverAddress)
                    .putString("last_security_token", token)
                    .apply()
            }
            updateKeepAliveServiceStatus()
        }
        pcConnector.onDisconnected = { reason ->
            isPcConnected.value = false
            pcStatus.value = "服务器已断开: $reason"
            notifyServiceStateChanged()
            updateKeepAliveServiceStatus()
        }
        pcConnector.onError = { msg ->
            isPcConnected.value = false
            pcStatus.value = "❌ $msg"
            notifyServiceStateChanged()
            updateKeepAliveServiceStatus()
        }
    }

    private fun updateKeepAliveServiceStatus() {
        val isPcActive = isPcConnected.value || pcStatus.value == "正在连接服务器..."
        val isWatchActive = isWatchConnected.value || isScanning.value
        val shouldBeRunning = isPcActive || isWatchActive
        
        if (shouldBeRunning) {
            startKeepAliveService()
            notifyServiceStateChanged()
        } else {
            stopKeepAliveService()
        }
    }

    private fun startKeepAliveService() {
        try {
            val intent = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动保活服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopKeepAliveService() {
        try {
            val intent = Intent(this, KeepAliveService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        bleManager.disconnect()
        pcConnector.destroy()
        stopKeepAliveService()
        super.onDestroy()
    }

    // ═════════════════════════════════════════════════════════
    // Jetpack Compose UI
    // ═════════════════════════════════════════════════════════

    @Composable
    fun HeartRateRelayTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit
    ) {
        val colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFFF2D55),
                onPrimary = Color.White,
                secondary = Color(0xFFFF5E7E),
                surface = Color(0xFF1E1B20),
                onSurface = Color(0xFFE6E1E5),
                background = Color(0xFF121214),
                onBackground = Color(0xFFE6E1E5),
                surfaceVariant = Color(0xFF2D2930),
                onSurfaceVariant = Color(0xFFCAC4D0),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFFFF2D55),
                onPrimary = Color.White,
                secondary = Color(0xFFFF5E7E),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1D1B20),
                background = Color(0xFFFEF7FF),
                onBackground = Color(0xFF1D1B20),
                surfaceVariant = Color(0xFFE7E0EC),
                onSurfaceVariant = Color(0xFF49454F),
            )
        }

        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val activity = view.context as? android.app.Activity
                activity?.window?.let { window ->
                    val backgroundColor = colorScheme.background
                    window.statusBarColor = backgroundColor.toArgb()
                    window.navigationBarColor = backgroundColor.toArgb()

                    val windowInsetsController = WindowCompat.getInsetsController(window, view)
                    // 如果是暗色主题，我们需要白色的状态栏文字图标（即 isAppearanceLightStatusBars = false）
                    // 如果是亮色主题，我们需要黑色的状态栏文字图标（即 isAppearanceLightStatusBars = true）
                    windowInsetsController.isAppearanceLightStatusBars = !darkTheme
                    windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }

        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val hrValue by currentHr
        val bleStatusText by bleStatus
        val pcStatusText by pcStatus
        val pcConnected by isPcConnected
        val watchConnected by isWatchConnected
        val scanning by isScanning

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("❤\uFE0F 心率中转助手", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── 心率大数字 ──────────────────────────────
                item {
                    HeartRateDisplay(hrValue, watchConnected)
                }

                // ─── 电脑连接状态 ────────────────────────────
                item {
                    StatusCard(
                        title = "💻 服务器连接",
                        status = pcStatusText,
                        isConnected = pcConnected,
                        extraContent = {
                            if (!pcConnected) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = manualServerUrl.value,
                                        onValueChange = { manualServerUrl.value = it },
                                        label = { Text("服务器地址", fontSize = 12.sp) },
                                        placeholder = { Text("例如: 192.168.1.100 或 wss://domain.com") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            cursorColor = MaterialTheme.colorScheme.primary,
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = securityToken.value,
                                            onValueChange = { securityToken.value = it },
                                            label = { Text("安全 Token", fontSize = 12.sp) },
                                            placeholder = { Text("后台获取的 API Token") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                cursorColor = MaterialTheme.colorScheme.primary,
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                val server = manualServerUrl.value.trim()
                                                val token = securityToken.value.trim()
                                                if (server.isNotEmpty()) {
                                                    pcConnector.securityToken = token
                                                    val url = buildWebSocketUrl(server, token)
                                                    pcConnector.connectToPc(url)
                                                    pcStatus.value = "正在连接服务器..."
                                                    notifyServiceStateChanged()
                                                    updateKeepAliveServiceStatus()
                                                    
                                                    // 保存配置
                                                    getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
                                                        .edit()
                                                        .putString("last_server_url", server)
                                                        .putString("last_security_token", token)
                                                        .apply()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text("连接")
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                // ─── 手表蓝牙状态 ────────────────────────────
                item {
                    StatusCard(
                        title = "⌚ 手表蓝牙",
                        status = bleStatusText,
                        isConnected = watchConnected,
                        extraContent = {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!watchConnected) {
                                    Button(
                                        onClick = {
                                            autoReconnectAddress = null // 手动扫描时取消自动重连，避免干扰
                                            isWatchIntentionallyDisconnected = false
                                            bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                                            discoveredDevices.clear()
                                            isScanning.value = true
                                            bleManager.startScan()
                                            notifyServiceStateChanged()
                                            updateKeepAliveServiceStatus()
                                        },
                                        enabled = !scanning,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                         if (scanning) {
                                             CustomCircularProgressIndicator(
                                                 modifier = Modifier.size(16.dp),
                                                 color = MaterialTheme.colorScheme.onPrimary
                                             )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("扫描中...")
                                        } else {
                                            Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("扫描手表")
                                        }
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            isWatchIntentionallyDisconnected = true
                                            bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                                            bleManager.disconnect()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("断开手表")
                                    }
                                }
                            }
                        }
                    )
                }

                // ─── 设备列表 ────────────────────────────────
                if (discoveredDevices.isNotEmpty() && !watchConnected) {
                    item {
                        Text(
                            "发现 ${discoveredDevices.size} 个设备",
                            color = Color(0xFF8888AA),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                    items(discoveredDevices) { (device, rssi) ->
                        DeviceItem(device, rssi)
                    }
                }

                // 底部留白
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    // ─── 心率大数字组件 ───────────────────────────────────────

    @Composable
    fun HeartRateDisplay(hr: Int, connected: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "heartbeat")
        val heartScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (hr > 0) (60000 / hr.coerceAtLeast(40)) else 1000,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "heartScale"
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 心形图标
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(if (hr > 0) heartScale else 1f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF2D55).copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("❤\uFE0F", fontSize = 36.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 心率数值
                Text(
                    text = if (hr > 0) "$hr" else "--",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = if (hr > 0) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )

                Text(
                    text = "BPM",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 心率描述
                val desc = when {
                    !connected -> "等待连接手表..."
                    hr == 0 -> "等待心率数据..."
                    hr < 60 -> "💤 心率偏低"
                    hr < 90 -> "😊 正常静息心率"
                    hr < 120 -> "🚶 轻度活动"
                    hr < 150 -> "🏃 中高强度运动"
                    else -> "🔥 高强度运动"
                }
                Text(
                    text = desc,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ─── 状态卡片组件 ────────────────────────────────────────

    @Composable
    fun StatusCard(
        title: String,
        status: String,
        isConnected: Boolean,
        extraContent: @Composable () -> Unit = {}
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 状态指示灯
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF34C759) else Color(0xFF666680))
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                extraContent()
            }
        }
    }

    // ─── 设备列表项 ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceItem(device: BluetoothDevice, rssi: Int) {
        val name = device.name ?: "未知设备"
        val isWearable = listOf("HUAWEI", "WATCH", "HONOR", "BAND", "GT").any {
            name.uppercase().contains(it)
        }

        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                                    autoReconnectAddress = null // 手动选择时取消自动重连
                                    isWatchIntentionallyDisconnected = false
                                    bleReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                                    bleManager.connect(device)
                                    bleStatus.value = "正在连接 $name..."
                                    notifyServiceStateChanged()
                                    // 记住当前选择的手表
                                    getSharedPreferences("hr_relay_prefs", Context.MODE_PRIVATE)
                                        .edit()
                                        .putString("last_watch_address", device.address)
                                        .putString("last_watch_name", name)
                                        .apply()
                                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (isWearable) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isWearable) "⌚" else "📱",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = device.address,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${rssi}dBm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CustomCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    val transition = rememberInfiniteTransition(label = "rotation")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    androidx.compose.foundation.Canvas(modifier = modifier.size(16.dp)) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 280f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}
