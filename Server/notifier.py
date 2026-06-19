import aiohttp
import time
import logging
import asyncio
from config import load_config

logger = logging.getLogger("HeartRateMonitor.Notifier")

# 全局推送冷却状态
last_notify_time = 0

async def send_push_notification(title, message, notif_config=None):
    """发送心率预警推送，支持多种机器人通道"""
    if notif_config is None:
        config = load_config()
        notif_config = config.get("notifications", {})
        
    async with aiohttp.ClientSession() as session:
        # 1. PushPlus (微信推送)
        pp = notif_config.get("pushplus", {})
        if pp.get("enabled") and pp.get("token"):
            try:
                url = "http://www.pushplus.plus/send"
                payload = {
                    "token": pp["token"],
                    "title": title,
                    "content": message,
                    "template": "html"
                }
                async with session.post(url, json=payload, timeout=10) as resp:
                    res = await resp.json()
                    logger.info(f"PushPlus 推送结果: {res}")
            except Exception as e:
                logger.error(f"PushPlus 推送失败: {e}")
                
        # 2. Telegram Bot
        tg = notif_config.get("telegram", {})
        if tg.get("enabled") and tg.get("token") and tg.get("chat_id"):
            try:
                url = f"https://api.telegram.org/bot{tg['token']}/sendMessage"
                payload = {
                    "chat_id": tg["chat_id"],
                    "text": f"⚠️ *{title}*\n{message}",
                    "parse_mode": "Markdown"
                }
                async with session.post(url, json=payload, timeout=10) as resp:
                    res = await resp.json()
                    logger.info(f"Telegram 推送结果: {res}")
            except Exception as e:
                logger.error(f"Telegram 推送失败: {e}")

        # 3. 钉钉机器人
        dd = notif_config.get("dingtalk", {})
        if dd.get("enabled") and dd.get("token"):
            try:
                url = f"https://oapi.dingtalk.com/robot/send?access_token={dd['token']}"
                payload = {
                    "msgtype": "text",
                    "text": {
                        "content": f"{title}\n{message}"
                    }
                }
                async with session.post(url, json=payload, timeout=10) as resp:
                    res = await resp.json()
                    logger.info(f"钉钉推送结果: {res}")
            except Exception as e:
                logger.error(f"钉钉推送失败: {e}")

        # 4. 飞书机器人
        fs = notif_config.get("feishu", {})
        if fs.get("enabled") and fs.get("token"):
            try:
                url = f"https://open.feishu.cn/open-apis/bot/v2/hook/{fs['token']}"
                payload = {
                    "msg_type": "text",
                    "content": {
                        "text": f"⚠️ {title}\n{message}"
                    }
                }
                async with session.post(url, json=payload, timeout=10) as resp:
                    res = await resp.json()
                    logger.info(f"飞书推送结果: {res}")
            except Exception as e:
                logger.error(f"飞书推送失败: {e}")


def check_cooldown_and_update(cooldown_minutes):
    """检查并更新推送冷却时间戳，防高频警报打扰"""
    global last_notify_time
    now = time.time()
    cooldown = cooldown_minutes * 60
    if now - last_notify_time >= cooldown:
        last_notify_time = now
        return True
    return False
