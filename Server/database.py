import sqlite3
import asyncio
import logging
from pathlib import Path

logger = logging.getLogger("HeartRateMonitor.Database")

class HeartRateDB:
    def __init__(self, db_path="heartrate.db"):
        self.db_path = Path(__file__).parent / db_path
        self.init_db()

    def init_db(self):
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS heartrate_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp REAL,
                    heart_rate INTEGER,
                    device_name TEXT
                )
            """)
            cursor.execute("""
                CREATE INDEX IF NOT EXISTS idx_hr_timestamp ON heartrate_records (timestamp)
            """)
            
            # 兼容升级：检测并添加 HRV 相关的列
            try:
                cursor.execute("ALTER TABLE heartrate_records ADD COLUMN hrv_rmssd REAL DEFAULT 0.0")
                cursor.execute("ALTER TABLE heartrate_records ADD COLUMN stress_index REAL DEFAULT 0.0")
                cursor.execute("ALTER TABLE heartrate_records ADD COLUMN fatigue_level REAL DEFAULT 0.0")
                conn.commit()
                logger.info("✅ 数据库成功升级，已添加 HRV / 压力 / 疲劳度相关字段")
            except sqlite3.OperationalError:
                # 列已存在，忽略
                pass

    def insert_record(self, timestamp, heart_rate, device_name, hrv_rmssd=0.0, stress_index=0.0, fatigue_level=0.0):
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT INTO heartrate_records (timestamp, heart_rate, device_name, hrv_rmssd, stress_index, fatigue_level)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (timestamp, heart_rate, device_name, hrv_rmssd, stress_index, fatigue_level))
                conn.commit()
        except Exception as e:
            logger.error(f"数据库写入失败: {e}")

    async def insert_record_async(self, timestamp, heart_rate, device_name, hrv_rmssd=0.0, stress_index=0.0, fatigue_level=0.0):
        await asyncio.to_thread(self.insert_record, timestamp, heart_rate, device_name, hrv_rmssd, stress_index, fatigue_level)

    def get_records_in_range(self, start_ts, end_ts):
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                cursor.execute("""
                    SELECT timestamp, heart_rate, device_name, hrv_rmssd, stress_index, fatigue_level
                    FROM heartrate_records
                    WHERE timestamp >= ? AND timestamp <= ?
                    ORDER BY timestamp ASC
                """, (start_ts, end_ts))
                rows = cursor.fetchall()
                records = [{
                    "time": r[0], 
                    "hr": r[1], 
                    "device": r[2],
                    "hrv": r[3] if len(r) > 3 else 0.0,
                    "stress": r[4] if len(r) > 4 else 0.0,
                    "fatigue": r[5] if len(r) > 5 else 0.0
                } for r in rows]
                
                # 如果点数过多，自动进行降采样，保持前端轻量渲染
                if len(records) > 1200:
                    step = len(records) // 1000
                    records = records[::step]
                    
                return records
        except Exception as e:
            logger.error(f"读取历史数据失败: {e}")
            return []

    async def get_records_in_range_async(self, start_ts, end_ts):
        return await asyncio.to_thread(self.get_records_in_range, start_ts, end_ts)

# 全局数据库实例
db = HeartRateDB()
