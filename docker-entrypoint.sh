#!/bin/sh
# ============================================================
# PocketBay 部署入口：解决「平台持久卷 /data 属主为 root，
# 非 root 的 appuser 写不进 /data/study.db（Permission denied）」问题。
#
# 容器默认以 root 启动（镜像内不设 USER）：
#   1) 把 /data 及子目录 chown 给 appuser（平台卷挂载会覆盖镜像内权限）
#   2) 用 runuser 降权为 appuser 再启动应用 —— 运行时仍是非 root
#
# 若非 root 启动（如平台强制指定了 --user），则跳过 chown 直接运行。
# ============================================================
set -e

if [ "$(id -u)" = "0" ]; then
    # 保证数据目录存在且属主正确（幂等，已有内容不动）
    mkdir -p /data/uploads /data/github_cache
    chown -R appuser:appuser /data
    echo "[entrypoint] /data 已 chown 给 appuser，降权启动应用"
    exec runuser -u appuser -- java -XX:MaxRAMPercentage=75 -jar /app/app.jar "$@"
fi

echo "[entrypoint] 以非 root 用户 $(id -u) 直接启动应用"
exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar "$@"
