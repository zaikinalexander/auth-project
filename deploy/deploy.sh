#!/usr/bin/env bash
# Сборка и выкладка приложения. Запуск от root:  bash deploy/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 1. Сборка бэкенда =="
(cd backend && mvn -q -DskipTests clean package)

echo "== 2. Сборка фронтенда =="
(cd frontend && npm install --no-audit --no-fund && npm run build)

echo "== 3. Установка артефактов =="
install -o authapp -g authapp -m 644 \
    backend/target/auth-backend-1.0.0.jar /opt/auth-backend/app.jar

rm -rf /var/www/auth-app/*
cp -r frontend/dist/* /var/www/auth-app/
chown -R root:root /var/www/auth-app
chmod -R a+rX /var/www/auth-app

echo "== 4. Перезапуск сервиса =="
systemctl enable --now auth-backend
systemctl restart auth-backend

echo "== 5. Проверка nginx =="
nginx -t
systemctl reload nginx

echo ""
echo "Готово. Последние логи сервиса:"
journalctl -u auth-backend -n 20 --no-pager
echo ""
echo "Проверка API:  curl -i http://127.0.0.1:8080/api/auth/me   (ожидается 401)"