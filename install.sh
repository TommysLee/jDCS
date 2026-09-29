#!/bin/bash
#
# jDCS 一键部署脚本（Linux / systemd）
# 执行前请确认：已 mvn clean package 生成 target/jdcs-*.jar
set -e

sudo useradd -r -s /bin/false jdcs 2>/dev/null || true
sudo usermod -aG dialout jdcs
sudo mkdir -p /opt/jdcs/dumps /var/log/jdcs
sudo chown -R jdcs:jdcs /opt/jdcs /var/log/jdcs

JAR=$(ls -t target/jdcs-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "请先执行 mvn clean package" >&2
    exit 1
fi
sudo cp "$JAR" /opt/jdcs/jdcs.jar
sudo cp jdcs.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now jdcs

echo "部署完成："
echo "  systemctl status jdcs"
echo "  journalctl -u jdcs -f"
