FROM python:3.11-slim

WORKDIR /app

# 复制依赖清单并安装
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制服务端源码与静态网页资源
COPY . .

# 暴露端口
EXPOSE 8765

# 设定数据挂载卷环境变量，隔离持久化数据与程序源码
ENV DATA_DIR=/app/data

# 启动服务端
CMD ["python", "main.py"]
