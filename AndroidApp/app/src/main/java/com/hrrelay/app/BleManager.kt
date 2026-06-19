package com.hrrelay.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE 蓝牙管理器
 * 负责扫描、连接华为手表并订阅心率数据通知
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        // 标准 BLE 心率服务 UUID
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    // 回调
    var onDeviceFound: ((BluetoothDevice, Int) -> Unit)? = null
    var onHeartRateReceived: ((Int, List<Int>?) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // ─── 扫描 ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan(targetAddress: String? = null) {
        if (scanning) return
        
        try {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                onStatusChanged?.invoke("设备不支持蓝牙")
                return
            }
            if (!adapter.isEnabled) {
                onStatusChanged?.invoke("蓝牙已关闭，请先开启蓝牙")
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                onStatusChanged?.invoke("无法获取扫描器 (蓝牙共享可能占用了蓝牙芯片)")
                return
            }

            scanning = true
            val statusMsg = if (targetAddress != null) "正在寻找并重连上次的手环..." else "正在扫描蓝牙设备..."
            onStatusChanged?.invoke(statusMsg)

            // 如果是特定地址重连，采用平衡模式以节省电量；如果是手动扫描，采用低延迟模式以快速发现
            val scanMode = if (targetAddress != null) {
                ScanSettings.SCAN_MODE_BALANCED
            } else {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }

            val settings = ScanSettings.Builder()
                .setScanMode(scanMode)
                .build()

            // 如果指定了目标设备 MAC 地址，则过滤其它无关蓝牙广播，实现底层芯片级过滤，极大节省 CPU 功耗
            val filters = if (targetAddress != null) {
                listOf(ScanFilter.Builder().setDeviceAddress(targetAddress).build())
            } else {
                null
            }

            scanner.startScan(filters, settings, scanCallback)

            // 10 秒后自动停止扫描
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopScan()
            }, 10000)
        } catch (e: SecurityException) {
            scanning = false
            Log.e(TAG, "扫描权限不足: ${e.message}", e)
            onStatusChanged?.invoke("⚠️ 权限不足，请确保已授予附近设备/定位权限")
        } catch (e: Exception) {
            scanning = false
            Log.e(TAG, "启动扫描失败: ${e.message}", e)
            onStatusChanged?.invoke("⚠️ 启动扫描失败: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "停止扫描失败: ${e.message}", e)
        }
        onStatusChanged?.invoke("扫描完成")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device
                if (device.name == null) return  // 忽略没有名字的设备
                onDeviceFound?.invoke(device, result.rssi)
            } catch (e: SecurityException) {
                Log.e(TAG, "获取设备名称权限不足: ${e.message}", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatusChanged?.invoke("扫描失败 (错误码: $errorCode)")
        }
    }

    // ─── 连接 ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        try {
            val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
            onStatusChanged?.invoke("正在连接 $name...")
            stopScan()
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            onStatusChanged?.invoke("⚠️ 连接权限不足")
            Log.e(TAG, "连接权限错误: ${e.message}", e)
        } catch (e: Exception) {
            onStatusChanged?.invoke("⚠️ 连接失败: ${e.localizedMessage}")
            Log.e(TAG, "连接失败: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.let {
                it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败: ${e.message}", e)
        }
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "已连接到 GATT 服务器")
                    onStatusChanged?.invoke("蓝牙已连接，正在发现服务...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "已断开 GATT 连接")
                    onStatusChanged?.invoke("手表蓝牙已断开")
                    onDisconnected?.invoke()
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged?.invoke("服务发现失败")
                return
            }

            val hrService = gatt.getService(HR_SERVICE_UUID)
            if (hrService == null) {
                onStatusChanged?.invoke("设备不支持标准心率服务，请检查心率广播设置")
                return
            }

            val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_UUID)
            if (hrChar == null) {
                onStatusChanged?.invoke("未找到心率测量特征值")
                return
            }

            // 订阅心率通知
            gatt.setCharacteristicNotification(hrChar, true)
            val descriptor = hrChar.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }

            val deviceName = gatt.device.name ?: gatt.device.address
            onStatusChanged?.invoke("✅ 心率订阅成功")
            onConnected?.invoke(deviceName)
            Log.i(TAG, "心率通知已订阅: $deviceName")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                val payload = characteristic.value
                val hr = parseHeartRate(payload)
                val rr = parseRrIntervals(payload)
                if (hr > 0) {
                    onHeartRateReceived?.invoke(hr, rr)
                }
            }
        }
    }

    // ─── 心率与 R-R 间期解析 ───────────────────────────────────────────────

    private fun parseHeartRate(data: ByteArray): Int {
        if (data.size < 2) return 0
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        return if (is16Bit && data.size >= 3) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }
    }

    private fun parseRrIntervals(data: ByteArray): List<Int> {
        if (data.size < 2) return emptyList()
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        val isEnergyExpended = (flags and 0x08) != 0
        val isRrPresent = (flags and 0x10) != 0
        
        if (!isRrPresent) return emptyList()
        
        var offset = if (is16Bit) 3 else 2
        if (isEnergyExpended) {
            offset += 2
        }
        
        val intervals = mutableListOf<Int>()
        while (offset + 1 < data.size) {
            val rrVal = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            // 蓝牙心率间期单位是 1/1024 秒，转换为毫秒
            val rrMs = Math.round(rrVal * 1000.0 / 1024.0).toInt()
            intervals.add(rrMs)
            offset += 2
        }
        return intervals
    }
}
