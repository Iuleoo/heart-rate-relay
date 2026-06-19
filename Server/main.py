import asyncio
import sys
import socket
import webbrowser
import argparse
import io
import logging
from pathlib import Path
from aiohttp import web

from config import load_config, get_local_ip
from ble_monitor import monitor
from routes import setup_routes, auth_middleware

# 日志初始化配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("HeartRateMonitor.Main")

UDP_DISCOVERY_PORT = 8766
UDP_BROADCAST_MSG_PREFIX = "HR_MONITOR_SERVER:"

async def udp_broadcaster(ws_port, interval=2.0):
    """在局域网内定期广播本机 WebSocket 地址，供手机端自动发现"""
    local_ip = get_local_ip()
    broadcast_msg = f"{UDP_BROADCAST_MSG_PREFIX}ws://{local_ip}:{ws_port}/ws/phone"
    logger.info(f"📡 局域网自动发现已启动 (UDP {UDP_DISCOVERY_PORT})")
    logger.info(f"📡 本机地址: ws://{local_ip}:{ws_port}/ws/phone")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setblocking(False)

    try:
        while True:
            try:
                sock.sendto(broadcast_msg.encode('utf-8'), ('255.255.255.255', UDP_DISCOVERY_PORT))
            except Exception as e:
                logger.warning(f"UDP 广播发送失败: {e}")
            await asyncio.sleep(interval)
    except asyncio.CancelledError:
        pass
    finally:
        sock.close()
        logger.info("📡 局域网自动发现已停止")


async def on_startup(app):
    """应用启动时保存事件循环引用并根据模式启动局域网广播"""
    monitor.set_loop(asyncio.get_event_loop())
    config = load_config()
    if not config.get("cloud_mode", False):
        app['udp_task'] = asyncio.create_task(udp_broadcaster(app['port']))
    else:
        logger.info("☁️ 检测到云端部署模式，已停用 UDP 局域网广播")


async def on_cleanup(app):
    """应用关闭时清理后台任务"""
    if 'udp_task' in app:
        app['udp_task'].cancel()
        try:
            await app['udp_task']
        except asyncio.CancelledError:
            pass


def create_app(port=8765):
    """创建 Web 应用"""
    app = web.Application(middlewares=[auth_middleware])
    app['port'] = port
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    # 挂载路由
    setup_routes(app)
    return app


if __name__ == "__main__":
    # 修复 Windows 控制台 GBK 编码问题
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="Heart Rate Monitor Server")
    parser.add_argument("--port", "-p", type=int, help="Web server port")
    args, unknown = parser.parse_known_args()

    config = load_config()
    cloud_mode = config.get("cloud_mode", False)

    print()
    print("  +--------------------------------------------------+")
    print("  |   Heart Rate Monitor - 心率监测器 v3.1 (云安全版) |")
    print("  |   运行模式: 安全中继及历史记录管理 (模块化拆分版)  |")
    print("  +--------------------------------------------------+")
    print()
    
    db_password = config.get("dashboard_password")
    if db_password and db_password.startswith("pbkdf2_sha256$"):
        db_password = "[已加密存储 (可在 server_config.json 修改或重置)]"
        
    print(f"  🔑 管理员登录密码: {db_password}")
    print(f"  🔑 安全 API Token: {config['api_token']}")
    print(f"  ☁️ 云端部署模式: {'开启 (仅监听 127.0.0.1)' if cloud_mode else '关闭 (对外公开 0.0.0.0)'}")
    print()

    port = args.port if args.port is not None else config.get("port", 8765)
    local_ip = get_local_ip()
    url = f"http://localhost:{port}"

    if cloud_mode:
        print(f"  [*] 服务本地代理地址: http://127.0.0.1:{port}")
        print("      (请使用 Nginx/Caddy 配置反向代理与 HTTPS SSL)")
    else:
        print(f"  [*] 本地网页地址: {url}")
        print(f"  [*] 局域网网页地址: http://{local_ip}:{port}")
        print(f"  [*] 手机连接地址: ws://{local_ip}:{port}/ws/phone?token={config['api_token']}")
        print()
        print(f"  📱 使用说明:")
        print(f"     1. 确保服务器公网可达，或手机和电脑连接在同一个局域网")
        print(f"     2. 在手机中转 App 中配置此服务器地址和 API Token")
        print(f"     3. App 会自动建立安全加密连接并实时传输数据")
    print()
    print(f"  按 Ctrl+C 停止服务器")
    print()

    # 自动打开浏览器
    if not cloud_mode:
        webbrowser.open(url)

    # 启动 web服务
    app = create_app(port=port)
    host_addr = "127.0.0.1" if cloud_mode else "0.0.0.0"
    web.run_app(app, host=host_addr, port=port, print=None)
