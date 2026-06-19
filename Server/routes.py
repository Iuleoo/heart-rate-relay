import json
import time
import secrets
import logging
import sqlite3
from pathlib import Path
from aiohttp import web, WSMsgType

from config import (
    load_config,
    verify_password,
    hash_password,
    get_local_ip,
    active_sessions,
    failed_logins,
    get_config_path
)
from database import db
from ble_monitor import monitor
from notifier import send_push_notification

logger = logging.getLogger("HeartRateMonitor.Routes")

@web.middleware
async def auth_middleware(request, handler):
    path = request.path
    
    # 1. 允许公开访问登录页及登录接口
    if path in ('/login', '/static/login.html'):
        return await handler(request)
        
    # 2. 检查是否为 API Token 认证 (优先从 Authorization Header 获取，其次从 query 参数获取)
    token = None
    auth_header = request.headers.get('Authorization')
    if auth_header and auth_header.startswith('Bearer '):
        token = auth_header.split('Bearer ')[1]
    
    if not token:
        # 兼容旧版本或测试使用 query 参数
        token = request.query.get('token')
            
    config = load_config()
    if token:
        if token == config.get('api_token'):
            response = await handler(request)
            if path == '/':
                session_id = secrets.token_hex(24)
                active_sessions.add(session_id)
                is_https = request.scheme == 'https' or request.headers.get('X-Forwarded-Proto') == 'https'
                response.set_cookie("session_id", session_id, httponly=True, secure=is_https, samesite="Strict")
            return response
        else:
            return web.json_response({"success": False, "message": "Invalid token"}, status=401)
            
    # 3. 检查 Session Cookie 认证 (适用于网页浏览器端)
    session_id = request.cookies.get('session_id')
    if session_id and session_id in active_sessions:
        return await handler(request)
        
    # 4. 未认证请求处理
    if path.startswith('/api/') or path.startswith('/ws'):
        return web.json_response({"success": False, "message": "Unauthorized"}, status=401)
        
    # 网页请求则重定向到登录页
    return web.HTTPFound('/static/login.html')


async def login_handler(request):
    """用户登录接口，支持 IP 防爆破限制"""
    ip = request.remote
    now = time.time()
    
    # 检查是否处于锁定期
    if ip in failed_logins:
        failed_count, lock_until = failed_logins[ip]
        if lock_until > now:
            wait_time = int(lock_until - now)
            return web.json_response({
                "success": False, 
                "message": f"错误次数过多，请在 {wait_time} 秒后再试。"
            }, status=429)
            
    try:
        data = await request.json()
        password = data.get("password")
    except Exception:
        return web.json_response({"success": False, "message": "无效的请求参数"}, status=400)

    config = load_config()
    if verify_password(password, config.get("dashboard_password")):
        # 登录成功，清除失败记录
        failed_logins.pop(ip, None)
        
        session_id = secrets.token_hex(24)
        active_sessions.add(session_id)
        response = web.json_response({"success": True})
        
        is_https = request.scheme == 'https' or request.headers.get('X-Forwarded-Proto') == 'https'
        response.set_cookie("session_id", session_id, httponly=True, secure=is_https, samesite="Strict")
        return response
    else:
        # 登录失败，增加失败计数
        failed_count, lock_until = failed_logins.get(ip, (0, 0.0))
        failed_count += 1
        
        if failed_count >= 5:
            lock_duration = 60 if failed_count < 10 else 600
            lock_until = now + lock_duration
            failed_logins[ip] = (failed_count, lock_until)
            return web.json_response({
                "success": False, 
                "message": f"密码错误。尝试失败次数过多，该 IP 已被锁定 {lock_duration} 秒。"
            }, status=401)
        else:
            failed_logins[ip] = (failed_count, lock_until)
            return web.json_response({
                "success": False, 
                "message": f"密码错误，还可以尝试 {5 - failed_count} 次"
            }, status=401)


async def logout_handler(request):
    """退出登录"""
    session_id = request.cookies.get("session_id")
    if session_id in active_sessions:
        active_sessions.discard(session_id)
    response = web.HTTPFound('/static/login.html')
    response.del_cookie("session_id")
    return response


async def live_api_handler(request):
    """获取当前实时心率"""
    return web.json_response({
        "connected": monitor.connected,
        "device_name": monitor.device_name,
        "heart_rate": monitor.current_hr,
        "timestamp": time.time()
    })


async def history_handler(request):
    """获取历史心率数据"""
    try:
        start_ts = float(request.query.get('start', 0))
        end_ts = float(request.query.get('end', 0))
    except ValueError:
        return web.json_response({"success": False, "message": "参数格式错误"}, status=400)

    if not start_ts or not end_ts:
        return web.json_response({"success": False, "message": "缺失开始或结束时间戳"}, status=400)

    records = await db.get_records_in_range_async(start_ts, end_ts)
    return web.json_response({"success": True, "records": records})


async def clear_history_handler(request):
    """清空历史心率数据库"""
    try:
        with sqlite3.connect(db.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM heartrate_records")
            conn.commit()
        monitor.hr_history = []
        logger.info("🗑️ 历史数据库已成功清空")
        return web.json_response({"success": True, "message": "所有历史记录已清空"})
    except Exception as e:
        logger.error(f"清空历史数据库失败: {e}")
        return web.json_response({"success": False, "message": f"清空失败: {str(e)}"}, status=500)


async def get_server_config_handler(request):
    """获取服务端当前配置 (除了密码)"""
    config = load_config()
    return web.json_response({
        "success": True,
        "api_token": config.get("api_token"),
        "cloud_mode": config.get("cloud_mode", False),
        "port": config.get("port", 8765),
        "safety_threshold": config.get("safety_threshold", 150),
        "notifications": config.get("notifications", {
            "pushplus": {"enabled": False, "token": ""},
            "telegram": {"enabled": False, "token": "", "chat_id": ""},
            "dingtalk": {"enabled": False, "token": ""},
            "feishu": {"enabled": False, "token": ""},
            "cooldown_minutes": 5
        })
    })


async def post_server_config_handler(request):
    """更新服务端配置"""
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"success": False, "message": "无效的请求参数"}, status=400)
        
    config_path = get_config_path()
    config = load_config()
    
    # 1. 处理云端模式切换
    if "cloud_mode" in data:
        config["cloud_mode"] = bool(data["cloud_mode"])
        
    # 2. 处理端口修改
    port_changed = False
    if "port" in data:
        try:
            new_port = int(data["port"])
            if 1 <= new_port <= 65535:
                if config.get("port") != new_port:
                    config["port"] = new_port
                    port_changed = True
            else:
                return web.json_response({"success": False, "message": "端口范围必须在 1 到 65535 之间"}, status=400)
        except ValueError:
            return web.json_response({"success": False, "message": "端口格式不正确"}, status=400)
            
    # 2.5. 处理安全阈值和推送设置
    if "safety_threshold" in data:
        try:
            config["safety_threshold"] = int(data["safety_threshold"])
        except ValueError:
            pass
            
    if "notifications" in data:
        config["notifications"] = data["notifications"]
        
    # 3. 处理 Token 重新生成
    if data.get("regenerate_token") == True:
        config["api_token"] = secrets.token_hex(16)
        
    # 4. 处理密码修改
    old_password = data.get("old_password")
    new_password = data.get("new_password")
    if new_password:
        if not verify_password(old_password, config.get("dashboard_password")):
            return web.json_response({"success": False, "message": "当前管理员密码验证失败"}, status=400)
        if len(new_password) < 6:
            return web.json_response({"success": False, "message": "新密码长度不能少于 6 位"}, status=400)
        config["dashboard_password"] = hash_password(new_password)
        
    # 写入文件
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4)
    except Exception as e:
        logger.error(f"写入 server_config.json 失败: {e}")
        return web.json_response({"success": False, "message": "保存配置文件失败"}, status=500)
        
    msg = "设置已成功保存"
    if port_changed:
        msg += "，修改的端口需重启服务后生效"
        
    return web.json_response({
        "success": True, 
        "message": msg,
        "api_token": config.get("api_token"),
        "cloud_mode": config.get("cloud_mode", False),
        "port": config.get("port", 8765),
        "safety_threshold": config.get("safety_threshold", 150),
        "notifications": config.get("notifications", {})
    })


async def test_push_handler(request):
    """测试消息推送接口"""
    try:
        data = await request.json()
        notif_config = data.get("notifications")
    except Exception:
        return web.json_response({"success": False, "message": "请求参数错误"}, status=400)
        
    if not notif_config:
        return web.json_response({"success": False, "message": "推送配置不能为空"}, status=400)
        
    asyncio.create_task(send_push_notification(
        title="💓 心率监测预警测试",
        message="这是一条测试预警推送消息，说明您的通知通道配置正确！",
        notif_config=notif_config
    ))
    return web.json_response({"success": True, "message": "测试推送指令已发出"})


async def index_handler(request):
    """首页"""
    return web.FileResponse(Path(__file__).parent / "static" / "index.html")


async def websocket_handler(request):
    """网页端与悬浮窗 WebSocket 处理"""
    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)
    monitor.ws_clients.add(ws)
    logger.info("前端已连接")

    # 发送初始状态
    await ws.send_str(json.dumps({
        "type": "init",
        "connected": monitor.connected,
        "device_name": monitor.device_name,
        "local_ip": get_local_ip(),
        "port": request.app['port'],
        "history": monitor.hr_history[-60:] if monitor.hr_history else [],
    }, ensure_ascii=False))

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                    action = data.get("action")

                    if action == "scan":
                        await ws.send_str(json.dumps({
                            "type": "scanning",
                            "message": "正在扫描附近的蓝牙设备...",
                        }, ensure_ascii=False))
                        devices = await monitor.scan_devices()
                        await ws.send_str(json.dumps({
                            "type": "devices",
                            "devices": devices,
                        }, ensure_ascii=False))

                    elif action == "connect":
                        address = data.get("address")
                        name = data.get("name", "")
                        if address:
                            await monitor.connect(address, name)

                    elif action == "disconnect":
                        await monitor.disconnect()

                except json.JSONDecodeError:
                    pass

            elif msg.type == WSMsgType.ERROR:
                logger.error(f"WebSocket 错误: {ws.exception()}")

    finally:
        monitor.ws_clients.discard(ws)
        logger.info("前端已断开")

    return ws


async def phone_websocket_handler(request):
    """处理来自 Android 手机端的 WebSocket 连接（心率中转）"""
    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)
    phone_name = "Android 手机"
    logger.info(f"📱 手机端已连接 ({request.remote})")

    # 通知浏览器前端手机已连接
    await monitor._broadcast({
        "type": "status",
        "status": "connected",
        "message": f"已通过手机中转连接 ({request.remote})",
        "device_name": f"📱 手机中转",
    })

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                    hr = data.get("hr")
                    rr_list = data.get("rr")
                    if hr is not None:
                        await monitor.handle_phone_heartrate(int(hr), phone_name, rr_list=rr_list)

                    name = data.get("device_name")
                    if name:
                        phone_name = name

                except (json.JSONDecodeError, ValueError, TypeError) as e:
                    logger.warning(f"📱 解析手机数据失败: {e}")
            elif msg.type == WSMsgType.ERROR:
                logger.error(f"📱 手机连接出错: {ws.exception()}")
    finally:
        logger.info(f"📱 手机端已断开 ({request.remote})")
        monitor.connected = False
        monitor.device_name = ""
        await monitor._broadcast({
            "type": "status",
            "status": "disconnected",
            "message": "手机中转已断开",
        })

    return ws


def setup_routes(app):
    """挂载 Web 服务路由"""
    static_path = Path(__file__).parent / "static"
    
    app.router.add_get("/", index_handler)
    app.router.add_post("/login", login_handler)
    app.router.add_get("/logout", logout_handler)
    app.router.add_get("/api/v1/heartrate/live", live_api_handler)
    app.router.add_get("/api/v1/heartrate/history", history_handler)
    app.router.add_post("/api/v1/heartrate/history/clear", clear_history_handler)
    app.router.add_get("/api/v1/server/config", get_server_config_handler)
    app.router.add_post("/api/v1/server/config", post_server_config_handler)
    app.router.add_post("/api/v1/server/test_push", test_push_handler)
    app.router.add_get("/ws", websocket_handler)
    app.router.add_get("/ws/phone", phone_websocket_handler)
    app.router.add_static("/static/", static_path)
