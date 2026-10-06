#!/usr/bin/env bash
# Генерация RSA-ключей для подписи JWT (RS256, 4096 бит).
# Запускать из корня проекта. Ключи НЕ коммитить в Git.
set -euo pipefail

mkdir -p keys

if [[ -f keys/private.pem ]]; then
  echo "Ключи уже существуют, пропускаю."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out keys/private.pem
openssl pkey -in keys/private.pem -pubout -out keys/public.pem
chmod 600 keys/private.pem
chmod 644 keys/public.pem

echo "Ключи созданы: keys/private.pem, keys/public.pem"