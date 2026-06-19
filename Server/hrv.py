import math
import logging

logger = logging.getLogger("HeartRateMonitor.HRV")

class HRVAnalyser:
    """心率变异性 (HRV) 与疲劳度/压力评估分析器"""

    def __init__(self, window_size=60):
        self.window_size = window_size
        self.rr_window = []  # 保存 R-R 间期 (毫秒)
        self.hr_history = []  # 保存 (timestamp, hr) 历史，用于无硬件 RR 时估算
        self.last_ts = 0.0

    def add_data(self, hr: int, timestamp: float, rr_list: list = None) -> dict:
        """
        添加心率数据，计算最新的 HRV 与疲劳度指标
        :param hr: 当前心率 (BPM)
        :param timestamp: 时间戳 (秒)
        :param rr_list: 硬件直接提供的 R-R 间期列表 (毫秒)
        :return: 包含 hrv_rmssd, stress_index, fatigue_level, is_real_hrv 的字典
        """
        is_real_hrv = False

        # 1. 优先采用硬件直接提供的 R-R 间期
        if rr_list and len(rr_list) > 0:
            is_real_hrv = True
            for rr in rr_list:
                if 300 <= rr <= 2000:  # 过滤异常跳动 (对应心率 30~200 BPM)
                    self.rr_window.append(rr)
        else:
            # 2. 若无硬件 R-R 间期，通过相邻心率包的变化估算宏观 HRV 波动
            # 心率广播包一般 1 秒发一次，我们根据前后两秒的 BPM 变化，计算等效的 R-R 波动
            self.hr_history.append((timestamp, hr))
            if len(self.hr_history) > self.window_size:
                self.hr_history.pop(0)

            if len(self.hr_history) >= 2:
                last_t, last_hr = self.hr_history[-2]
                curr_t, curr_hr = self.hr_history[-1]
                dt = curr_t - last_t

                # 仅在时间间隔合理（比如 0.5 ~ 3 秒以内）时计算，防止断连后数据突变
                if 0.5 <= dt <= 3.0:
                    # 将 BPM 平均值转换为瞬时 R-R 间隔估计 (毫秒)
                    last_rr_est = 60000.0 / max(30, last_hr)
                    curr_rr_est = 60000.0 / max(30, curr_hr)
                    
                    # 模拟在这个时间间隔内发生了多少次跳动，并推入 R-R 窗口
                    beats = int((dt * curr_hr) / 60.0)
                    if beats <= 0:
                        beats = 1
                    
                    # 线性插值生成过渡段 R-R 间隔，并推入窗口
                    for i in range(beats):
                        factor = (i + 1) / beats
                        rr_est = last_rr_est + (curr_rr_est - last_rr_est) * factor
                        self.rr_window.append(int(rr_est))

        # 保持滑动窗口大小
        while len(self.rr_window) > self.window_size:
            self.rr_window.pop(0)

        # 3. 计算 HRV 指标 (以 RMSSD 为核心)
        rmssd = 0.0
        sdnn = 0.0
        if len(self.rr_window) >= 5:
            # 计算相邻 R-R 差值的平方平均根 (RMSSD)
            diff_sq_sum = 0.0
            for i in range(len(self.rr_window) - 1):
                diff = self.rr_window[i + 1] - self.rr_window[i]
                diff_sq_sum += diff * diff
            rmssd = math.sqrt(diff_sq_sum / (len(self.rr_window) - 1))

            # 计算标准差 (SDNN)
            mean_rr = sum(self.rr_window) / len(self.rr_window)
            variance = sum((x - mean_rr) ** 2 for x in self.rr_window) / len(self.rr_window)
            sdnn = math.sqrt(variance)

        # 4. 根据心率和 RMSSD 计算压力指数与疲劳度
        # RMSSD 正常范围一般在 20ms (疲劳/高压) 到 80ms (放松/健康) 之间
        # 心率正常静息为 60~70，运动时 100+。心率越高，压力/疲劳越高；HRV 越低，压力/疲劳越高。
        if len(self.rr_window) >= 5:
            # 映射基础压力分值
            # 基础公式：压力 = (80 - RMSSD) * 0.8 + (hr - 60) * 0.4
            stress_base = (80.0 - min(80.0, rmssd)) * 0.9 + (hr - 55.0) * 0.5
            
            # 如果是模拟的 HRV，由于经过了秒级平均，波动会被大大过滤，RMSSD 算出来通常偏小，需要进行补偿修正
            if not is_real_hrv:
                # 模拟的 RMSSD 通常在 2ms ~ 25ms，我们将其等比例放大还原到正常生理范围，方便统一评估
                rmssd_display = rmssd * 4.0 + 10.0
                stress_base = (80.0 - min(80.0, rmssd_display)) * 0.8 + (hr - 55.0) * 0.5
            else:
                rmssd_display = rmssd

            stress_index = max(10.0, min(99.0, stress_base))
            
            # 疲劳度算法：疲劳度与压力和心率持续高位相关
            # 若心率长期处于无氧区，疲劳度上升极快；若 HRV 极低说明无法适应负荷
            fatigue_base = (80.0 - min(80.0, rmssd_display)) * 0.7 + (hr - 50.0) * 0.6
            fatigue_level = max(5.0, min(100.0, fatigue_base))
        else:
            # 初始默认值
            rmssd_display = 0.0
            stress_index = 35.0  # 默认适中
            fatigue_level = 20.0  # 默认轻微

        # 5. 生成疲劳描述
        if fatigue_level < 35:
            desc = "精力充沛 (恢复良好)"
        elif fatigue_level < 60:
            desc = "轻度疲劳 (适度紧张)"
        elif fatigue_level < 80:
            desc = "中度疲劳 (压力偏高)"
        else:
            desc = "重度疲劳 (极度疲劳 / 预警)"

        return {
            "hrv_rmssd": round(rmssd_display, 1),
            "stress_index": round(stress_index, 1),
            "fatigue_level": round(fatigue_level, 1),
            "fatigue_desc": desc,
            "is_real_hrv": is_real_hrv,
            "is_ready": len(self.rr_window) >= 5
        }

# 全局分析器单例
hrv_analyser = HRVAnalyser()
