sudo useradd -r -s /bin/false jdcs
sudo usermod -aG dialout jdcs
sudo mkdir -p /opt/jdcs/dumps /var/log/jdcs
sudo chown -R jdcs:jdcs /opt/jdcs /var/log/jdcs

sudo cp target/jdcs-1.0.0-SNAPSHOT.jar /opt/jdcs/jdcs.jar
sudo cp jdcs.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now jdcs
