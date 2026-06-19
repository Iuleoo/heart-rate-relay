"""
❤️ 心率监测器 - Heart Rate Monitor
此文件作为入口兼容引导文件，核心业务逻辑已模块化拆分至 main.py 及相关子模块中。
"""

import sys
from pathlib import Path

# 将当前目录加入系统路径，防止导入子模块出错
sys.path.insert(0, str(Path(__file__).parent))

if __name__ == "__main__":
    from main import main
    # 引导执行 main 模块的启动流程
    import subprocess
    import os
    
    main_py_path = Path(__file__).parent / "main.py"
    # 使用与当前解释器相同的 Python 运行 main.py 并传递所有参数
    cmd = [sys.executable, str(main_py_path)] + sys.argv[1:]
    try:
        sys.exit(subprocess.call(cmd))
    except KeyboardInterrupt:
        pass
