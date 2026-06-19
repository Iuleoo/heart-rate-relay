import json
import secrets
import hashlib
import base64
import os
import socket
import logging
from pathlib import Path

logger = logging.getLogger("HeartRateMonitor.Config")

active_sessions = set()
failed_logins = {}

def get_local_ip():
    """获取本机局域网 IP 地址"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.254.254.254', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def hash_password(password: str, salt: bytes = None) -> str:
    if salt is None:
        salt = os.urandom(16)
    key = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, 100000)
    return f"pbkdf2_sha256$100000${base64.b64encode(salt).decode('utf-8')}${base64.b64encode(key).decode('utf-8')}"

def verify_password(password: str, encoded_hash: str) -> bool:
    try:
        if not encoded_hash.startswith("pbkdf2_sha256$"):
            return password == encoded_hash
        parts = encoded_hash.split("$")
        if len(parts) != 4:
            return False
        iterations = int(parts[1])
        salt = base64.b64decode(parts[2].encode('utf-8'))
        original_hash = base64.b64decode(parts[3].encode('utf-8'))
        key = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, iterations)
        return key == original_hash
    except Exception:
        return False

def load_config():
    config_path = Path(__file__).parent / "server_config.json"
    if not config_path.exists():
        password = secrets.token_urlsafe(6)
        api_token = secrets.token_hex(16)
        hashed_password = hash_password(password)
        config_data = {
            "dashboard_password": hashed_password,
            "api_token": api_token,
            "cloud_mode": False,
            "port": 8765
        }
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config_data, f, indent=4)
        logger.info("🆕 已自动生成 server_config.json 配置文件")
        print(f"\n  [!] 首次启动自动生成管理员密码: {password} (已哈希加密保存至配置文件)\n")
        return config_data
    else:
        try:
            with open(config_path, "r", encoding="utf-8-sig") as f:
                data = json.load(f)
                modified = False
                pw = data.get("dashboard_password")
                if pw and not pw.startswith("pbkdf2_sha256$"):
                    data["dashboard_password"] = hash_password(pw)
                    modified = True
                    logger.info("🔒 已自动将配置文件中的明文密码升级为 PBKDF2 安全哈希存储")
                if "cloud_mode" not in data:
                    data["cloud_mode"] = False
                    modified = True
                if "port" not in data:
                    data["port"] = 8765
                    modified = True
                if modified:
                    with open(config_path, "w", encoding="utf-8") as fw:
                        json.dump(data, fw, indent=4)
                return data
        except Exception as e:
            logger.error(f"读取 server_config.json 失败: {e}，将使用临时配置")
            return {"dashboard_password": "admin", "api_token": "token123", "cloud_mode": False, "port": 8765}
