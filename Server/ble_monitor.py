import asyncio
import json
import time
import logging
from database import db
from config import load_config

# BLE 库导入
try:
    from bleak import BleakScanner, BleakClient
    BLE_AVAILABLE = True
except ImportError:
    BLE_AVAILABLE = False

# 标准 BLE 心率服务 UUID
HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b0000"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b0000"

logger = logging.getLogger("HeartRateMonitor.BLE")

class HeartRateMonitor:
    """BLE 心率监测与中转数据管理类"""

    def __init__(self):
        self.client = None
        self.connected = False
        self.current_hr = 0
        self.hr_history = []
        self.ws_clients = set()
        self.device_name = ""
        self.scanning = False
        self._loop = None

    def set_loop(self, loop):
        self._loop = loop

    # ─── 扫描设备 ─────────────────────────────────────────────

    async def scan_devices(self):
        """扫描附近的 BLE 设备"""
        if not BLE_AVAILABLE:
            return []

        if self.scanning:
            return []

        self.scanning = True
        devices_found = {}

        try:
            logger.info("开始扫描 BLE 设备...")
            devices = await BleakScanner.discover(timeout=8.0)

            for d in devices:
                name = d.name or "未知设备"
                addr = d.address

                # 检查是否广播了心率服务 UUID
                service_uuids = []
                if hasattr(d, "metadata") and d.metadata:
                    service_uuids = [
                        str(u).lower() for u in d.metadata.get("uuids", [])
                    ]

                is_hr = HR_SERVICE_UUID in service_uuids

                # 判断是否可能是可穿戴设备
                keywords = [
                    "HUAWEI", "WATCH", "HONOR", "BAND", "HR",
                    "MI BAND", "AMAZFIT", "GARMIN", "FITBIT",
                    "SAMSUNG", "GALAXY", "GT", "VENU",
                ]
                is_wearable = any(kw in name.upper() for kw in keywords)

                rssi = None
                if hasattr(d, "rssi"):
                    rssi = d.rssi
                elif hasattr(d, "metadata") and d.metadata:
                    rssi = d.metadata.get("rssi")

                devices_found[addr] = {
                    "name": name,
                    "address": addr,
                    "is_hr": is_hr,
                    "is_wearable": is_wearable,
                    "rssi": rssi,
                }

            logger.info(f"扫描完成，发现 {len(devices_found)} 个设备")

        except Exception as e:
            logger.error(f"扫描出错: {e}")
            await self._broadcast({
                "type": "error",
                "message": f"扫描出错: {str(e)}，请检查蓝牙是否已开启",
            })
        finally:
            self.scanning = False

        # 排序：心率设备优先 > 可穿戴设备 > 其他
        result = sorted(
            devices_found.values(),
            key=lambda x: (not x["is_hr"], not x["is_wearable"], x["name"]),
        )
        return result

    # ─── 连接设备 ─────────────────────────────────────────────

    async def connect(self, address, name=""):
        """连接到 BLE 设备并订阅心率通知"""
        if not BLE_AVAILABLE:
            await self._broadcast({
                "type": "error",
                "message": "BLE 库未安装，请先运行: pip install bleak",
            })
            return

        try:
            await self._broadcast({
                "type": "status",
                "status": "connecting",
                "message": f"正在连接 {name}...",
            })

            self.client = BleakClient(
                address,
                disconnected_callback=self._on_disconnect,
            )
            await self.client.connect(timeout=15.0)

            logger.info(f"已连接到 {name} ({address})")

            # 枚举服务，查找心率服务
            hr_char_found = False
            for service in self.client.services:
                if service.uuid.lower() == HR_SERVICE_UUID:
                    for char in service.characteristics:
                        if char.uuid.lower() == HR_MEASUREMENT_UUID:
                            hr_char_found = True
                            break

            if not hr_char_found:
                await self._broadcast({
                    "type": "error",
                    "message": "该设备不支持标准心率服务。\n"
                               "请确保华为手表已开启「心率广播」功能：\n"
                               "手表 → 设置 → 健康监测 → 心率广播",
                })
                await self.client.disconnect()
                return

            # 订阅心率测量通知
            await self.client.start_notify(
                HR_MEASUREMENT_UUID, self._hr_notification_handler
            )

            self.connected = True
            self.device_name = name
            self.hr_history = []

            await self._broadcast({
                "type": "status",
                "status": "connected",
                "message": f"已连接到 {name}",
                "device_name": name,
                "device_address": address,
            })

        except asyncio.TimeoutError:
            logger.error("连接超时")
            await self._broadcast({
                "type": "error",
                "message": "连接超时，请确保设备在范围内并已开启心率广播",
            })
            self.connected = False
        except Exception as e:
            logger.error(f"连接失败: {e}")
            await self._broadcast({
                "type": "error",
                "message": f"连接失败: {str(e)}",
            })
            self.connected = False

    # ─── 断开连接 ─────────────────────────────────────────────

    def _on_disconnect(self, client):
        """处理设备意外断开"""
        self.connected = False
        logger.info("设备已断开连接")
        if self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(
                self._broadcast({
                    "type": "status",
                    "status": "disconnected",
                    "message": "设备已断开连接",
                }),
                self._loop,
            )

    async def disconnect(self):
        """主动断开连接"""
        if self.client and self.connected:
            try:
                await self.client.stop_notify(HR_MEASUREMENT_UUID)
            except Exception:
                pass
            try:
                await self.client.disconnect()
            except Exception:
                pass
            self.connected = False
            self.device_name = ""
            await self._broadcast({
                "type": "status",
                "status": "disconnected",
                "message": "已断开连接",
            })
            logger.info("已主动断开连接")

    # ─── 心率数据解析 ──────────────────────────────────────────

    async def _hr_notification_handler(self, sender, data):
        """处理 BLE 心率通知回调"""
        hr, rr_list = self._parse_hr_measurement(data)
        if hr is None or hr == 0:
            return

        self.current_hr = hr
        timestamp = time.time()

        # 调用 HRV 评估
        from hrv import hrv_analyser
        hrv_results = hrv_analyser.add_data(hr, timestamp, rr_list)

        # 写入 SQLite 数据库
        await db.insert_record_async(
            timestamp, hr, self.device_name or "蓝牙直连设备",
            hrv_rmssd=hrv_results["hrv_rmssd"],
            stress_index=hrv_results["stress_index"],
            fatigue_level=hrv_results["fatigue_level"]
        )

        self.hr_history.append({"hr": hr, "time": timestamp})
        # 保留最近 600 个数据点
        if len(self.hr_history) > 600:
            self.hr_history = self.hr_history[-600:]

        # 计算统计数据
        hrs = [h["hr"] for h in self.hr_history]
        stats = {
            "min": min(hrs),
            "max": max(hrs),
            "avg": round(sum(hrs) / len(hrs), 1),
            "count": len(hrs),
        }

        await self._broadcast({
            "type": "heartrate",
            "hr": hr,
            "time": timestamp,
            "stats": stats,
            "hrv": hrv_results
        })

        # 触发预警及疲劳推送检查
        asyncio.create_task(self.check_and_trigger_alerts(hr, hrv_results))
        logger.debug(f"心率: {hr} BPM, HRV: {hrv_results['hrv_rmssd']} ms")

    @staticmethod
    def _parse_hr_measurement(data):
        """
        解析 BLE 心率测量特征值。
        参考: Bluetooth SIG Heart Rate Measurement Characteristic
        返回 (heart_rate, rr_intervals)
        """
        if len(data) < 2:
            return None, []

        flags = data[0]
        hr_format_16bit = flags & 0x01
        is_ee_present = flags & 0x08
        is_rr_present = flags & 0x10

        offset = 1
        if hr_format_16bit:
            if len(data) < 3:
                return None, []
            hr = data[offset] | (data[offset + 1] << 8)
            offset += 2
        else:
            hr = data[offset]
            offset += 1

        if is_ee_present:
            offset += 2

        rr_intervals = []
        if is_rr_present:
            while offset + 1 < len(data):
                rr_val = data[offset] | (data[offset + 1] << 8)
                # 原始值单位是 1/1024 秒。转为毫秒。
                rr_ms = round(rr_val * 1000.0 / 1024.0)
                rr_intervals.append(rr_ms)
                offset += 2

        return hr, rr_intervals

    # ─── WebSocket 广播 ────────────────────────────────────────

    async def _broadcast(self, data):
        """向所有 WebSocket 客户端广播消息"""
        if not self.ws_clients:
            return
        msg = json.dumps(data, ensure_ascii=False)
        dead = set()
        for ws in self.ws_clients:
            try:
                await ws.send_str(msg)
            except Exception:
                dead.add(ws)
        self.ws_clients -= dead

    # ─── 手机端心率数据处理 ─────────────────────────────────────

    async def handle_phone_heartrate(self, hr, device_name="手机中转", rr_list=None):
        """处理来自手机端 App 转发的心率数据"""
        if hr is None or hr == 0:
            return

        self.current_hr = hr
        self.connected = True
        if not self.device_name:
            self.device_name = device_name
        timestamp = time.time()

        # 调用 HRV 评估
        from hrv import hrv_analyser
        hrv_results = hrv_analyser.add_data(hr, timestamp, rr_list)

        # 写入 SQLite 数据库
        await db.insert_record_async(
            timestamp, hr, self.device_name or device_name,
            hrv_rmssd=hrv_results["hrv_rmssd"],
            stress_index=hrv_results["stress_index"],
            fatigue_level=hrv_results["fatigue_level"]
        )

        self.hr_history.append({"hr": hr, "time": timestamp})
        if len(self.hr_history) > 600:
            self.hr_history = self.hr_history[-600:]

        hrs = [h["hr"] for h in self.hr_history]
        stats = {
            "min": min(hrs),
            "max": max(hrs),
            "avg": round(sum(hrs) / len(hrs), 1),
            "count": len(hrs),
        }

        await self._broadcast({
            "type": "heartrate",
            "hr": hr,
            "time": timestamp,
            "stats": stats,
            "hrv": hrv_results
        })

        # 触发预警推送检查
        asyncio.create_task(self.check_and_trigger_alerts(hr, hrv_results))
        logger.debug(f"📱 手机中转心率: {hr} BPM, HRV: {hrv_results['hrv_rmssd']} ms")

    async def check_and_trigger_alerts(self, hr, hrv_results=None):
        """检查并触发超标预警及疲劳警报推送"""
        config = load_config()
        threshold = config.get("safety_threshold", 150)
        
        trigger_alert = False
        title = ""
        message = ""
        
        # 1. 检查心率是否超标
        if hr >= threshold:
            trigger_alert = True
            title = "🚨 心率过高预警！"
            message = f"警告：您的实时心率已达 **{hr} BPM**，超过安全阈值 {threshold} BPM！请注意休息并调整运动强度。"
            
        # 2. 检查疲劳状态是否达到极度状态
        elif hrv_results and hrv_results.get("fatigue_level", 0.0) >= 80.0:
            trigger_alert = True
            title = "⚠️ 极度身体疲劳预警！"
            fatigue = hrv_results["fatigue_level"]
            stress = hrv_results["stress_index"]
            desc = hrv_results["fatigue_desc"]
            message = f"警告：您的身体疲劳指数已达 **{fatigue}%**（{desc}），压力指数 **{stress}**。自主神经系统已过度紧张，建议立即休息以防运动损伤或突发心脑血管风险。"
            
        if trigger_alert:
            notif = config.get("notifications", {})
            cooldown_mins = notif.get("cooldown_minutes", 5)
            
            from notifier import check_cooldown_and_update, send_push_notification
            if check_cooldown_and_update(cooldown_mins):
                logger.warning(f"触发警报推送: {title} {message}")
                asyncio.create_task(send_push_notification(title, message))

# 全局监测器实例
monitor = HeartRateMonitor()
