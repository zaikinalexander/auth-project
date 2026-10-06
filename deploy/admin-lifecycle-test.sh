#!/usr/bin/env bash
# =====================================================================
#  admin-lifecycle-test.sh — проверка жизненного цикла учётных записей
#
#  Прогоняет полный путь: назначение второго администратора, вход и
#  полномочия, понижение в USER, немедленная потеря админки при живом
#  access-токене, отзыв страниц, отключение и включение, удаление,
#  отзыв сессий удалённого и защита от потери последнего администратора.
#
#  Все учётные записи временные и удаляются в конце. Существующие
#  пользователи не затрагиваются.
#
#  Зависимости: bash, curl, jq, openssl, доступ к psql от root.
#  Реквизиты действующего администратора — из /root/app-credentials.txt
#  либо из переменных OWNER_ADMIN_EMAIL / OWNER_ADMIN_PASSWORD.
#  Запуск:  bash deploy/admin-lifecycle-test.sh
#  Код выхода: 0 — все проверки пройдены, 1 — есть провалы.
# =====================================================================
set -uo pipefail
B=https://app.seoniks.ru
CRED_FILE="${CRED_FILE:-/root/app-credentials.txt}"
[ -r "$CRED_FILE" ] && . "$CRED_FILE"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

PASS=0; FAIL=0
chk(){ if [ "$2" = "$3" ]; then printf '  PASS  %-62s =%s\n' "$1" "$2"; PASS=$((PASS+1));
       else printf '  FAIL  %-62s ожидалось=%s получено=%s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi; }
api(){ local m=$1 p=$2 t=$3; shift 3; curl -s -o "$W/b" -w '%{http_code}' -X "$m" "$B$p" -H "Authorization: Bearer $t" "$@"; }
login(){ curl -s -c "$3" -o "$W/l.json" -w '%{http_code}' -X POST "$B/api/auth/login" \
         -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}"; }
tok(){ jq -r '.accessToken // empty' < "$1"; }

L_ADMIN="lifecycle-admin@seoniks.ru"; L_PASS=$(openssl rand -hex 16)
L_USER="lifecycle-victim@seoniks.ru"; L_UPASS=$(openssl rand -hex 16)

echo "== Жизненный цикл администратора: создание -> работа -> понижение -> удаление =="

login "$OWNER_ADMIN_EMAIL" "$OWNER_ADMIN_PASSWORD" "$W/jo" >/dev/null; TO=$(tok "$W/l.json")

echo "-- 1. назначение --"
chk "создан второй ADMIN со всеми страницами" 201 \
  "$(curl -s -o "$W/b" -w '%{http_code}' -X POST "$B/api/admin/users" -H "Authorization: Bearer $TO" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$L_ADMIN\",\"password\":\"$L_PASS\",\"role\":\"ADMIN\",\"pages\":[\"TEST_PAGE_1\",\"TEST_PAGE_2\",\"TEST_PAGE_3\"]}")"
LID=$(jq -r '.id' < "$W/b")
echo "     id=$LID"

echo "-- 2. вход и полномочия --"
chk "новый ADMIN входит" 200 "$(login "$L_ADMIN" "$L_PASS" "$W/jl")"
TL=$(tok "$W/l.json")
chk "новый ADMIN видит список пользователей" 200 "$(api GET /api/admin/users "$TL")"
chk "новый ADMIN создаёт пользователя" 201 \
  "$(curl -s -o "$W/b" -w '%{http_code}' -X POST "$B/api/admin/users" -H "Authorization: Bearer $TL" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$L_USER\",\"password\":\"$L_UPASS\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_2\"]}")"
VID=$(jq -r '.id' < "$W/b")
chk "новому ADMIN доступны все страницы" 200 "$(api GET /api/pages/TEST_PAGE_3 "$TL")"

echo "-- 3. понижение в USER (владелец понижает второго админа) --"
chk "PATCH role=USER чужой учётки" 200 \
  "$(api PATCH "/api/admin/users/$LID" "$TO" -H 'Content-Type: application/json' -d '{"role":"USER"}')"
echo "     роль в БД: $(sudo -u postgres psql -d authdb -tAc "select role from users where email='$L_ADMIN';")"
chk "СТАРЫЙ access-токен пониженного сразу потерял админку" 403 "$(api GET /api/admin/users "$TL")"
chk "старый access-токен всё ещё идентифицирует пользователя" 200 "$(api GET /api/auth/me "$TL")"
chk "роль в /api/auth/me уже USER" "USER" "$(api GET /api/auth/me "$TL" >/dev/null; jq -r '.role' < "$W/b")"
chk "явно выданная страница после понижения осталась" 200 "$(api GET /api/pages/TEST_PAGE_3 "$TL")"
echo "     (роль и набор страниц — независимые сущности: понижение не отзывает выданные страницы)"
chk "отобрали страницы через PUT /pages" 200 \
  "$(api PUT "/api/admin/users/$LID/pages" "$TO" -H 'Content-Type: application/json' -d '{"pages":[]}')"
chk "после отзыва TEST_PAGE_3 недоступна" 403 "$(api GET /api/pages/TEST_PAGE_3 "$TL")"
chk "и /api/auth/me показывает пустой список страниц" "[]" \
  "$(api GET /api/auth/me "$TL" >/dev/null; jq -c '.pages' < "$W/b")"

echo "-- 4. отключение учётки --"
chk "PATCH enabled=false" 200 \
  "$(api PATCH "/api/admin/users/$VID" "$TO" -H 'Content-Type: application/json' -d '{"enabled":false}')"
chk "вход отключённым пользователем" 401 "$(login "$L_USER" "$L_UPASS" "$W/jv")"
chk "включение обратно" 200 \
  "$(api PATCH "/api/admin/users/$VID" "$TO" -H 'Content-Type: application/json' -d '{"enabled":true}')"
chk "вход после включения" 200 "$(login "$L_USER" "$L_UPASS" "$W/jv")"

echo "-- 5. переназначение страниц --"
chk "PUT /pages оставил только TEST_PAGE_2" 200 \
  "$(api PUT "/api/admin/users/$VID/pages" "$TO" -H 'Content-Type: application/json' -d '{"pages":["TEST_PAGE_2"]}')"
TV=$(tok "$W/l.json")
chk "TEST_PAGE_2 доступна"  200 "$(api GET /api/pages/TEST_PAGE_2 "$TV")"
chk "TEST_PAGE_1 недоступна" 403 "$(api GET /api/pages/TEST_PAGE_1 "$TV")"

echo "-- 6. удаление --"
chk "DELETE второго ADMIN" 204 "$(api DELETE "/api/admin/users/$LID" "$TO")"
chk "DELETE созданного им пользователя" 204 "$(api DELETE "/api/admin/users/$VID" "$TO")"
chk "access-токен удалённого больше не работает" 401 "$(api GET /api/auth/me "$TL")"
chk "вход удалённым" 401 "$(login "$L_ADMIN" "$L_PASS" "$W/jd")"
chk "refresh удалённого отозван" 401 "$(curl -s -o /dev/null -w '%{http_code}' -b "$W/jl" -X POST "$B/api/auth/refresh")"

echo "-- 7. защита от потери последнего администратора --"
OWN_ID=$(jq -r '.id' < <(curl -s "$B/api/auth/me" -H "Authorization: Bearer $TO"))
chk "единственный ADMIN не может удалить себя" 400 "$(api DELETE "/api/admin/users/$OWN_ID" "$TO")"
chk "единственный ADMIN не может понизить себя" 400 \
  "$(api PATCH "/api/admin/users/$OWN_ID" "$TO" -H 'Content-Type: application/json' -d '{"role":"USER"}')"
chk "единственный ADMIN не может отключить себя" 400 \
  "$(api PATCH "/api/admin/users/$OWN_ID" "$TO" -H 'Content-Type: application/json' -d '{"enabled":false}')"
echo "     админов в БД осталось: $(sudo -u postgres psql -d authdb -tAc "select count(*) from users where role='ADMIN';")"

echo
echo "== Состав БД после проверки (временные учётки удалены) =="
sudo -u postgres psql -d authdb -c "select email,role,enabled from users order by created_at;"
printf '== ИТОГО: PASS=%d FAIL=%d ==\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
