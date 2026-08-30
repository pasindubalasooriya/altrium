#!/bin/bash
set -euxo pipefail
exec > >(tee /var/log/altrium-bootstrap.log) 2>&1

# Swap first. A 1 GB box installing MySQL and a JVM will otherwise be killed mid-apt.
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' > /etc/sysctl.d/99-altrium.conf

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx mysql-server openjdk-21-jre-headless

# MySQL inside 1 GB. performance_schema alone is worth about 200 MB here.
cat > /etc/mysql/mysql.conf.d/99-altrium.cnf <<'CNF'
[mysqld]
bind-address = 127.0.0.1
performance_schema = OFF
innodb_buffer_pool_size = 128M
innodb_log_buffer_size = 8M
max_connections = 30
table_open_cache = 200
CNF

systemctl restart mysql
systemctl enable mysql

# xtrace off across the secret. On the first run it was left on and the generated password
# was echoed into this log, which then had to be chmod 600 after the fact.
set +x
DB_PASSWORD="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 28)"

mysql <<SQL
CREATE DATABASE IF NOT EXISTS altrium CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'altrium'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON altrium.* TO 'altrium'@'localhost';
FLUSH PRIVILEGES;
SQL

install -d -m 750 /etc/altrium
install -d -m 755 /opt/altrium
install -d -m 755 /var/www/altrium

# Read by the systemd unit only. Never leaves the instance.
cat > /etc/altrium/altrium.env <<ENV
ALTRIUM_DB_URL=jdbc:mysql://127.0.0.1:3306/altrium?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
ALTRIUM_DB_USER=altrium
ALTRIUM_DB_PASSWORD=${DB_PASSWORD}
ALTRIUM_PORT=8080
SERVER_ADDRESS=127.0.0.1
ALTRIUM_SEED_ENABLED=true
ENV
chmod 640 /etc/altrium/altrium.env
set -x

useradd --system --no-create-home --shell /usr/sbin/nologin altrium || true
chown -R altrium:altrium /opt/altrium
chown root:altrium /etc/altrium/altrium.env

chmod 600 /var/log/altrium-bootstrap.log
touch /var/lib/cloud/altrium-bootstrap-done
