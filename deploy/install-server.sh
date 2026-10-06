#!/usr/bin/env bash
# Однократная подготовка сервера (Ubuntu 24.04).
# Запуск от root:  bash deploy/install-server.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export DEBIAN_FRONTEND=noninteractive

echo "== 1. Пакеты =="
apt-get update
apt-get install -y openjdk-21-jdk-headless maven nodejs npm \
    nginx postgresql redis-server openssl fail2ban curl

echo "== 2. Пользователь сервиса =="
id -u authapp >/dev/null 2>&1 || useradd --system --home /opt/auth-backend --shell /usr/sbin/nologin authapp

mkdir -p /opt/auth-backend /etc/auth-backend/keys /var/www/auth-app

echo "== 3. RSA-ключи для JWT =="
if [[ ! -f /etc/auth-backend/keys/private.pem ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
      -out /etc/auth-backend/keys/private.pem
  openssl pkey -in /etc/auth-backend/keys/private.pem \
      -pubout -out /etc/auth-backend/keys/public.pem
fi
chmod 600 /etc/auth-backend/keys/private.pem
chmod 644 /etc/auth-backend/keys/public.pem
chown -R root:authapp /etc/auth-backend
chmod 750 /etc/auth-backend

echo "== 4. Файл секретов =="
if [[ ! -f /etc/auth-backend/env ]]; then
  DB_PASS=$(openssl rand -base64 24 | tr -d '=+/' | cut -c1-32)
  REDIS_PASS=$(openssl rand -base64 24 | tr -d '=+/' | cut -c1-32)
  ADMIN_PASS=$(openssl rand -base64 24 | tr -d '=+/' | cut -c1-20)
  cat > /etc/auth-backend/env <<ENVEOF
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=authdb
DB_USER=authuser
DB_PASSWORD=${DB_PASS}
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASS}
JWT_PRIVATE_KEY_PATH=/etc/auth-backend/keys/private.pem
JWT_PUBLIC_KEY_PATH=/etc/auth-backend/keys/public.pem
ADMIN_EMAIL=admin@seoniks.ru
ADMIN_INITIAL_PASSWORD=${ADMIN_PASS}
ENVEOF
  chmod 640 /etc/auth-backend/env
  chown root:authapp /etc/auth-backend/env
  echo "!!! СОХРАНИТЕ пароль первичного администратора: ${ADMIN_PASS}"
  echo "    Он также лежит в /etc/auth-backend/env"
fi

set -a
source /etc/auth-backend/env
set +a

echo "== 5. PostgreSQL: база и пользователь =="
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='authuser'" | grep -q 1; then
  sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQLEOF
CREATE ROLE authuser LOGIN PASSWORD '${DB_PASSWORD}';
CREATE DATABASE authdb OWNER authuser;
SQLEOF
fi

echo "== 6. Redis: пароль =="
if ! grep -q '^requirepass' /etc/redis/redis.conf; then
  echo "requirepass ${REDIS_PASSWORD}" >> /etc/redis/redis.conf
fi
systemctl restart redis-server

echo "== 7. systemd и nginx =="
install -m 644 deploy/auth-backend.service /etc/systemd/system/auth-backend.service
install -m 644 deploy/nginx-app.conf /etc/nginx/conf.d/zz-auth-app.conf
systemctl daemon-reload
nginx -t
systemctl reload nginx

echo "== 8. Файрвол и fail2ban =="
if command -v ufw >/dev/null; then
  ufw allow 22/tcp
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw allow 1500/tcp   # порт панели ispmanager; проверьте: ss -tlnp | grep ispmgr
  ufw allow 1501/tcp   # и измените номера, если панель на других портах
  ufw --force enable
fi
systemctl enable --now fail2ban

chown -R authapp:authapp /opt/auth-backend

echo ""
echo "Установка завершена."
echo "Следующий шаг: bash deploy/deploy.sh"