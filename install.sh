#!/bin/bash
#
# jDCS 一键部署脚本（Linux / systemd）
# 执行前请确认：已 mvn clean package 生成 target/jdcs-*.jar
set -e

sudo useradd -r -s /bin/false jdcs 2>/dev/null || true
sudo usermod -aG dialout jdcs
sudo mkdir -p /opt/jdcs/dumps /var/log/jdcs
sudo chown -R jdcs:jdcs /opt/jdcs /var/log/jdcs
# 日志目录 755：属主 jdcs 可写，其他人只读（最小权限）
sudo chmod 755 /var/log/jdcs

JAR=$(ls -t target/jdcs-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "请先执行 mvn clean package" >&2
    exit 1
fi
sudo cp "$JAR" /opt/jdcs/jdcs.jar
sudo cp jdcs.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now jdcs

echo ""
echo "部署完成："
echo "  systemctl status jdcs"
echo "  tail -f /var/log/jdcs/stdout.log"
echo "  curl http://localhost:8080/health"
echo ""
echo "提示：日志由 jdcs.service 的 StandardOutput=append 写入 /var/log/jdcs/，该文件不会自动滚动。"
echo "      如需日志轮转，推荐在 logback-spring.xml 中增加 RollingFileAppender"
echo "      （由 logback 自行管理滚动与保留，无需任何外部工具），"
echo "      同时把 jdcs.service 的 StandardOutput/StandardError 改为 journal，避免同一份日志写两遍。"
echo "      详见文档 12.6 节。"
