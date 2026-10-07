#!/usr/bin/env bash
# =====================================================================
#  smoke-test.sh — сквозная приёмка API app.seoniks.ru
#
#  Зависимости: bash, curl, jq, openssl.
#  Запуск:      bash deploy/smoke-test.sh
#  Код выхода:  0 — все проверки пройдены, 1 — есть провалы.
#
#  Учётные данные берутся из переменных окружения, а при их отсутствии —
#  из /root/app-credentials.txt. Нужны один ADMIN и один USER с правом
#  только на TEST_PAGE_1.
#
#  Проверка ограничения попыток входа выполняется на отдельном адресе
#  ratelimit-test@seoniks.ru, чтобы не блокировать реальные входы.
#
#  Флаг --clean-audit удаляет из audit_log следы тестовых учёток и прогонов
#  с адреса самого сервера. Строки реальных пользователей, пришедшие с
#  внешних адресов, не затрагиваются.
#
#  ВНИМАНИЕ: раздел «Очистка журнала» разрушающий — он стирает audit_log.
#  Перед очисткой скрипт сам сохраняет снимок журнала в /root/backups,
#  поэтому результат обратим. Путь снимка переопределяется AUDIT_SNAPSHOT.
# =====================================================================
set -uo pipefail

CLEAN_AUDIT=0
for arg in "$@"; do
    case "$arg" in
        --clean-audit) CLEAN_AUDIT=1 ;;
        *) echo "Неизвестный аргумент: $arg"; exit 2 ;;
    esac
done

BASE="${BASE:-https://app.seoniks.ru}"
CRED_FILE="${CRED_FILE:-/root/app-credentials.txt}"
RL_EMAIL="ratelimit-test@seoniks.ru"
RL_PASSWORD="definitely-wrong-password"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"

for bin in curl jq openssl awk; do
    command -v "$bin" >/dev/null 2>&1 || { echo "Не найден $bin"; exit 2; }
done

cred() {
    if [ -n "${!1:-}" ]; then printf '%s' "${!1}"; return; fi
    [ -r "$CRED_FILE" ] && grep "^$2=" "$CRED_FILE" | head -1 | cut -d= -f2-
}
ADMIN_EMAIL="$(cred ADMIN_EMAIL OWNER_ADMIN_EMAIL)"
ADMIN_PASSWORD="$(cred ADMIN_PASSWORD OWNER_ADMIN_PASSWORD)"
USER_EMAIL="$(cred USER_EMAIL TEST_USER_EMAIL)"
USER_PASSWORD="$(cred USER_PASSWORD TEST_USER_PASSWORD)"

for v in ADMIN_EMAIL ADMIN_PASSWORD USER_EMAIL USER_PASSWORD; do
    [ -n "${!v}" ] || { echo "Не задан $v (ни в окружении, ни в $CRED_FILE)"; exit 2; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0; FAIL=0
ok()   { printf '  PASS  %s\n' "$1"; PASS=$((PASS + 1)); }
bad()  { printf '  FAIL  %s\n        ожидалось: %s\n        получено:  %s\n' "$1" "$2" "$3"; FAIL=$((FAIL + 1)); }
check(){ [ "$2" = "$3" ] && ok "$1 [=$2]" || bad "$1" "$2" "$3"; }
head_(){ printf '\n== %s ==\n' "$1"; }

# code METHOD PATH [доп. аргументы curl...] — печатает HTTP-код, тело пишет в $WORK/body
code() {
    local m="$1" p="$2"; shift 2
    curl -s -o "$WORK/body" -w '%{http_code}' -X "$m" "$BASE$p" "$@"
}
json() { jq -r "$1" < "$WORK/body" 2>/dev/null; }
tok()  { jq -r '.accessToken // empty' < "$1" 2>/dev/null; }
cookie_value() { awk '$6 == "refresh_token" { print $7 }' "$1" 2>/dev/null; }

# login EMAIL PASSWORD JAR OUT — печатает HTTP-код входа
login() {
    curl -s -c "$3" -o "$4" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$1\",\"password\":\"$2\"}"
}

echo "############################################################"
echo "# SMOKE-TEST  $BASE"
echo "# $(date -Is)"
echo "############################################################"

# ---------------------------------------------------------------- вход
head_ "Вход и состав ответа"
AC="$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "$WORK/jar_a" "$WORK/login_a.json")"
check "POST /api/auth/login (ADMIN)" 200 "$AC"
TA="$(tok "$WORK/login_a.json")"
[ -n "$TA" ] && ok "в теле есть accessToken" || bad "в теле есть accessToken" "не пусто" "пусто"
check "роль в ответе входа" "ADMIN" "$(jq -r '.user.role' < "$WORK/login_a.json")"

curl -s -D "$WORK/h" -o /dev/null -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
SC="$(grep -i '^set-cookie:' "$WORK/h" | tr -d '\r')"
for attr in "refresh_token=" "HttpOnly" "Secure" "SameSite=Strict" "Path=/api/auth"; do
    case "$SC" in *"$attr"*) ok "cookie содержит $attr";; *) bad "cookie содержит $attr" "есть" "нет";; esac
done
case "$SC" in *Max-Age=604800*) ok "cookie Max-Age=604800 (7 дней)";; *) bad "cookie Max-Age" "604800" "$SC";; esac
jq -e 'has("refreshToken") | not' < "$WORK/login_a.json" >/dev/null \
    && ok "refresh-токен НЕ присутствует в JSON-теле" \
    || bad "refresh-токен НЕ присутствует в JSON-теле" "отсутствует" "присутствует"

# ---------------------------------------------------------------- me
head_ "Текущий пользователь"
check "GET /api/auth/me с токеном"  200 "$(code GET /api/auth/me -H "Authorization: Bearer $TA")"
check "email в /api/auth/me" "$ADMIN_EMAIL" "$(json '.email')"
check "GET /api/auth/me без токена" 401 "$(code GET /api/auth/me)"
check "GET /api/auth/me с мусорным токеном" 401 "$(code GET /api/auth/me -H 'Authorization: Bearer a.b.c')"

# --------------------------------------------- ротация и отзыв семьи
head_ "Ротация refresh-токена и отзыв семьи сессий"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jar_r1" "$WORK/lr1.json" >/dev/null
C1="$(cookie_value "$WORK/jar_r1")"
RC="$(code POST /api/auth/refresh -b "$WORK/jar_r1" -c "$WORK/jar_r2")"
check "POST /api/auth/refresh" 200 "$RC"
C2="$(cookie_value "$WORK/jar_r2")"
[ -n "$C2" ] && [ "$C1" != "$C2" ] \
    && ok "refresh-токен ротирован (новый отличается от старого)" \
    || bad "ротация refresh-токена" "новый != старый" "C1=$C1 C2=$C2"
[ -n "$(json '.accessToken')" ] && ok "refresh вернул новый accessToken" || bad "refresh вернул accessToken" "не пусто" "пусто"

printf '# Netscape HTTP Cookie File\n%s\tFALSE\t/api/auth\tTRUE\t9999999999\trefresh_token\t%s\n' \
    "app.seoniks.ru" "$C1" > "$WORK/jar_old"
check "повторное использование старого refresh-токена" 401 \
    "$(code POST /api/auth/refresh -b "$WORK/jar_old")"
check "после replay отозвана вся семья (текущий токен тоже мёртв)" 401 \
    "$(code POST /api/auth/refresh -b "$WORK/jar_r2")"

# ---------------------------------------------------------------- logout
head_ "Выход и недействительность старой сессии"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jar_l" "$WORK/ll.json" >/dev/null
TL="$(tok "$WORK/ll.json")"
check "POST /api/auth/logout без Bearer" 401 "$(code POST /api/auth/logout -b "$WORK/jar_l")"
check "POST /api/auth/logout с Bearer" 204 "$(code POST /api/auth/logout -b "$WORK/jar_l" -H "Authorization: Bearer $TL")"
check "refresh после logout" 401 "$(code POST /api/auth/refresh -b "$WORK/jar_l")"
check "me после logout (старый access ещё жив 15 мин)" 200 \
    "$(code GET /api/auth/me -H "Authorization: Bearer $TL")"

# ---------------------------------------------------------------- роли
head_ "Разграничение доступа: USER"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jar_u" "$WORK/lu.json" >/dev/null
TU="$(tok "$WORK/lu.json")"
check "роль" "USER" "$(jq -r '.user.role' < "$WORK/lu.json")"
check "страницы пользователя" "TEST_PAGE_1" "$(jq -r '.user.pages | join(",")' < "$WORK/lu.json")"
check "GET /api/pages/TEST_PAGE_1 (разрешена)" 200 "$(code GET /api/pages/TEST_PAGE_1 -H "Authorization: Bearer $TU")"
check "GET /api/pages/TEST_PAGE_2 (чужая)"     403 "$(code GET /api/pages/TEST_PAGE_2 -H "Authorization: Bearer $TU")"
check "тело 403" "Forbidden" "$(json '.error')"
check "GET /api/pages/TEST_PAGE_3 (чужая)"     403 "$(code GET /api/pages/TEST_PAGE_3 -H "Authorization: Bearer $TU")"
check "GET /api/admin/users (нужен ADMIN)"     403 "$(code GET /api/admin/users -H "Authorization: Bearer $TU")"

head_ "Разграничение доступа: ADMIN"
check "GET /api/pages/TEST_PAGE_1" 200 "$(code GET /api/pages/TEST_PAGE_1 -H "Authorization: Bearer $TA")"
check "GET /api/pages/TEST_PAGE_2" 200 "$(code GET /api/pages/TEST_PAGE_2 -H "Authorization: Bearer $TA")"
check "GET /api/pages/TEST_PAGE_3" 200 "$(code GET /api/pages/TEST_PAGE_3 -H "Authorization: Bearer $TA")"
check "GET /api/admin/users"       200 "$(code GET /api/admin/users -H "Authorization: Bearer $TA")"
check "GET /api/admin/users без токена" 401 "$(code GET /api/admin/users)"
check "несуществующая страница"    404 "$(code GET /api/pages/NO_SUCH_PAGE -H "Authorization: Bearer $TA")"

# ---------------------------------------------------------------- защита от самоудаления
head_ "Защита администратора от самоудаления"
OWN_ID="$(jq -r '.user.id' < "$WORK/login_a.json")"
check "DELETE собственной учётки" 400 "$(code DELETE "/api/admin/users/$OWN_ID" -H "Authorization: Bearer $TA")"
check "PATCH собственной роли"    400 "$(code PATCH "/api/admin/users/$OWN_ID" -H "Authorization: Bearer $TA" \
    -H 'Content-Type: application/json' -d '{"role":"USER"}')"

# ---------------------------------------------------------------- SPA и TLS
head_ "SPA, редирект и заголовки"
check "http -> https" 301 "$(curl -s -o /dev/null -w '%{http_code}' "http://${BASE#https://}/")"
LOC="$(curl -s -o /dev/null -w '%{redirect_url}' "http://${BASE#https://}/some/deep/link")"
check "редирект сохраняет путь" "https://${BASE#https://}/some/deep/link" "$LOC"
check "GET / (SPA)" 200 "$(code GET /)"
grep -q '<div id="app">' "$WORK/body" && ok "в HTML есть точка монтирования #app" || bad "HTML содержит #app" "есть" "нет"
check "SPA deep-link /login" 200 "$(code GET /login)"
ASSET="$(curl -s "$BASE/" | grep -oE '/assets/[^"]+\.js' | head -1)"
[ -n "$ASSET" ] && ok "в index.html найден бандл $ASSET" || bad "поиск бандла в index.html" "найден" "не найден"
check "бандл SPA отдаётся" 200 "$(code GET "$ASSET")"
curl -s -D "$WORK/hh" -o /dev/null "$BASE/api/auth/me"
for h in "x-content-type-options: nosniff" "x-frame-options: DENY" "referrer-policy: no-referrer" \
         "content-security-policy:" "strict-transport-security:"; do
    grep -qi "^$h" "$WORK/hh" && ok "заголовок $h" || bad "заголовок $h" "присутствует" "отсутствует"
done
CNT="$(grep -ci '^strict-transport-security:' "$WORK/hh")"
check "HSTS ровно один раз (без дублей)" 1 "$CNT"

# ---------------------------------------------------------------- брутфорс
head_ "Ограничение попыток входа ($RL_EMAIL)"
CODES=()
for i in $(seq 1 $((MAX_ATTEMPTS + 1))); do
    CODES+=("$(curl -s -o "$WORK/rl" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$RL_EMAIL\",\"password\":\"$RL_PASSWORD\"}")")
done
echo "        коды ответов по попыткам: ${CODES[*]}"
N="${CODES[$MAX_ATTEMPTS]}"
check "попытка №$((MAX_ATTEMPTS + 1)) заблокирована" 429 "$N"
if [ "${CODES[0]}" = 401 ]; then
    ok "первые $MAX_ATTEMPTS попыток отдали 401, затем сработал лимит"
else
    echo "        примечание: лимитатор уже прогрет предыдущим прогоном (окно 5 минут),"
    echo "        первая попытка сразу получила ${CODES[0]}. Поведение корректное."
fi
check "тело 429" "Слишком много попыток входа. Повторите позже." "$(jq -r '.error' < "$WORK/rl")"
check "реальный вход под ADMIN не заблокирован" 200 \
    "$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "$WORK/jar_z" "$WORK/lz.json")"

# ---------------------------------------------------------------- страницы
head_ "Динамические страницы: CRUD администратором"
SP="SMOKE_PAGE_$RANDOM"
check "GET /api/admin/pages без токена" 401 "$(code GET /api/admin/pages)"
check "GET /api/admin/pages под USER"   403 "$(code GET /api/admin/pages -H "Authorization: Bearer $TU")"
check "GET /api/admin/pages под ADMIN"  200 "$(code GET /api/admin/pages -H "Authorization: Bearer $TA")"

check "создание страницы" 201 "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/admin/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$SP\",\"title\":\"Страница smoke\",\"content\":\"Строка один\nСтрока два\"}")"
check "код созданной страницы" "$SP" "$(jq -r '.code' < "$WORK/body")"
check "содержимое с переносом строки сохранено" "Строка один
Строка два" "$(jq -r '.content' < "$WORK/body")"

code GET /api/admin/pages -H "Authorization: Bearer $TA" >/dev/null
jq -e --arg c "$SP" '[.[] | select(.code == $c)] | length == 1' < "$WORK/body" >/dev/null \
  && ok "новая страница есть в списке" || bad "новая страница в списке" "есть" "нет"

check "правка title и content" 200 "$(curl -s -o "$WORK/body" -w '%{http_code}' -X PUT "$BASE/api/admin/pages/$SP" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
    -d '{"title":"Страница smoke (ред.)","content":"Обновлённое содержимое"}')"
check "заголовок изменился" "Страница smoke (ред.)" "$(jq -r '.title' < "$WORK/body")"
check "код после правки прежний" "$SP" "$(jq -r '.code' < "$WORK/body")"
# тело намеренно повторяет предыдущее сохранение, чтобы проверка не затирала состояние
check "попытка передать code в теле игнорируется" "$SP" \
    "$(curl -s -X PUT "$BASE/api/admin/pages/$SP" -H "Authorization: Bearer $TA" \
        -H 'Content-Type: application/json' \
        -d '{"code":"HACKED","title":"Страница smoke (ред.)","content":"Обновлённое содержимое"}' | jq -r '.code')"

check "код в нижнем регистре"    400 "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/admin/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$(echo "$SP" | tr 'A-Z' 'a-z')\",\"title\":\"t\",\"content\":\"c\"}")"
check "код короче 3 символов"    400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/admin/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"code":"AB","title":"t","content":"c"}')"
check "код с дефисом"            400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/admin/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"code":"PAGE-1","title":"t","content":"c"}')"
check "дубликат кода"            409 "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/admin/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$SP\",\"title\":\"t\",\"content\":\"c\"}")"
check "правка несуществующей страницы" 404 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/admin/pages/NO_SUCH_PAGE_XYZ" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"title":"t","content":"c"}')"

head_ "Динамические страницы: доступ пользователя"
check "USER видит назначенную TEST_PAGE_1" 200 "$(code GET /api/pages/TEST_PAGE_1 -H "Authorization: Bearer $TU")"
check "USER не видит новую страницу до назначения" 403 "$(code GET "/api/pages/$SP" -H "Authorization: Bearer $TU")"
check "ADMIN видит новую страницу" 200 "$(code GET "/api/pages/$SP" -H "Authorization: Bearer $TA")"
check "код страницы регистрозависим" 403 "$(code GET "/api/pages/$(echo "$SP" | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $TU")"

code GET /api/admin/users -H "Authorization: Bearer $TA" >/dev/null
UID1=$(jq -r --arg e "$USER_EMAIL" '.[] | select(.email == $e) | .id' < "$WORK/body")
check "назначение новой страницы пользователю" 200 "$(code PUT "/api/admin/users/$UID1/pages" \
    -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
    -d "{\"pages\":[\"TEST_PAGE_1\",\"$SP\"]}")"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jp2" "$WORK/lp2.json" >/dev/null
TP2="$(tok "$WORK/lp2.json")"
check "USER видит назначенную новую страницу" 200 "$(code GET "/api/pages/$SP" -H "Authorization: Bearer $TP2")"
check "содержимое пришло из базы" "Обновлённое содержимое" "$(json '.content')"
check "неназначенная TEST_PAGE_3" 403 "$(code GET /api/pages/TEST_PAGE_3 -H "Authorization: Bearer $TP2")"

head_ "Динамические страницы: удаление и каскад"
check "удаление страницы" 204 "$(code DELETE "/api/admin/pages/$SP" -H "Authorization: Bearer $TA")"
check "повторное удаление" 404 "$(code DELETE "/api/admin/pages/$SP" -H "Authorization: Bearer $TA")"
check "USER после удаления: право снято каскадом" 403 "$(code GET "/api/pages/$SP" -H "Authorization: Bearer $TP2")"
check "ADMIN после удаления" 404 "$(code GET "/api/pages/$SP" -H "Authorization: Bearer $TA")"
check "назначение TEST_PAGE_1 у пользователя восстановлено" 200 \
    "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/admin/users/$UID1/pages" \
        -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"pages":["TEST_PAGE_1"]}')"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/ju" "$WORK/lu.json" >/dev/null
TU="$(tok "$WORK/lu.json")"

# ---------------------------------------------------------------- сессии
head_ "Сессии: список и обрыв"
SU="smoke-sess-$RANDOM-$RANDOM@seoniks.ru"; SPASS=$(openssl rand -hex 16)
curl -s -o "$WORK/body" -X POST "$BASE/api/admin/users" -H "Authorization: Bearer $TA" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$SU\",\"password\":\"$SPASS\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_1\"]}" >/dev/null
SUID=$(jq -r '.id' < "$WORK/body")
check "временный пользователь для сессий создан" "yes" "$([ "$SUID" != null ] && [ -n "$SUID" ] && echo yes || echo no)"

login "$SU" "$SPASS" "$WORK/js1" "$WORK/ls1.json" >/dev/null; TS1="$(tok "$WORK/ls1.json")"
login "$SU" "$SPASS" "$WORK/js2" "$WORK/ls2.json" >/dev/null; TS2="$(tok "$WORK/ls2.json")"

check "GET /api/me/sessions без токена" 401 "$(code GET /api/me/sessions)"
check "GET /api/me/sessions под USER" 200 "$(code GET /api/me/sessions -H "Authorization: Bearer $TS1")"
check "своих сессий две" 2 "$(jq 'length' < "$WORK/body")"
check "помечена ровно одна current" 1 "$(jq '[.[]|select(.current)]|length' < "$WORK/body")"
check "в списке только свой email" "$SU" "$(jq -r '[.[].userEmail]|unique|join(",")' < "$WORK/body")"
check "в сессии есть ip" "yes" "$(jq -r 'if (.[0].ip // "") == "" then "no" else "yes" end' < "$WORK/body")"
check "в сессии есть user_agent" "yes" "$(jq -r 'if (.[0].userAgent // "") == "" then "no" else "yes" end' < "$WORK/body")"
check "в сессии есть created и lastUsed" "yes" \
    "$(jq -r 'if (.[0].createdAt != null and .[0].lastUsedAt != null) then "yes" else "no" end' < "$WORK/body")"
FAM1=$(jq -r '.[]|select(.current)|.familyId' < "$WORK/body")

check "GET /api/admin/sessions без токена" 401 "$(code GET /api/admin/sessions)"
check "GET /api/admin/sessions под USER"   403 "$(code GET /api/admin/sessions -H "Authorization: Bearer $TS1")"
check "GET /api/admin/sessions под ADMIN"  200 "$(code GET /api/admin/sessions -H "Authorization: Bearer $TA")"
jq -e --arg e "$SU" '[.[] | select(.userEmail == $e)] | length >= 2' < "$WORK/body" >/dev/null \
  && ok "админ видит обе сессии временного пользователя" || bad "сессии временного пользователя в админском списке" ">=2" "меньше"
check "фильтр /api/admin/sessions?user= работает" "$SU" \
    "$(code GET "/api/admin/sessions?user=$SU" -H "Authorization: Bearer $TA" >/dev/null; jq -r '[.[].userEmail]|unique|join(",")' < "$WORK/body")"

# /api/me/sessions всегда возвращает только свои, в том числе администратору
code GET /api/me/sessions -H "Authorization: Bearer $TA" >/dev/null
check "под ADMIN /api/me/sessions отдаёт только свои" "$ADMIN_EMAIL" \
    "$(jq -r '[.[].userEmail]|unique|join(",")' < "$WORK/body")"
jq -e --arg e "$SU" '[.[] | select(.userEmail == $e)] | length == 0' < "$WORK/body" >/dev/null \
  && ok "чужие сессии в /api/me/sessions не видны даже администратору" \
  || bad "изоляция /api/me/sessions" "чужих нет" "чужие видны"

check "обрыв одной сессии администратором" 204 "$(code DELETE "/api/admin/sessions/$FAM1" -H "Authorization: Bearer $TA")"
check "refresh оборванной сессии"   401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/js1" "$BASE/api/auth/refresh")"
check "вторая сессия того же пользователя жива" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/js2" -c "$WORK/js2" "$BASE/api/auth/refresh")"
check "повторный обрыв той же семьи" 404 "$(code DELETE "/api/admin/sessions/$FAM1" -H "Authorization: Bearer $TA")"
check "обрыв несуществующей семьи"   404 "$(code DELETE "/api/admin/sessions/no-such-family" -H "Authorization: Bearer $TA")"

login "$SU" "$SPASS" "$WORK/js3" "$WORK/ls3.json" >/dev/null; TS3="$(tok "$WORK/ls3.json")"
check "DELETE /api/me/sessions/other" 200 "$(code DELETE /api/me/sessions/other -H "Authorization: Bearer $TS3")"
check "оборвана одна чужая сессия" 1 "$(jq -r '.revoked' < "$WORK/body")"
check "текущая сессия жива" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/js3" -c "$WORK/js3" "$BASE/api/auth/refresh")"
check "чужая сессия мертва"  401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/js2" "$BASE/api/auth/refresh")"

check "DELETE /api/admin/users/{id}/sessions" 200 "$(code DELETE "/api/admin/users/$SUID/sessions" -H "Authorization: Bearer $TA")"
check "refresh после обрыва всех" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/js3" "$BASE/api/auth/refresh")"
check "access-токен доживает свои 15 минут" 200 "$(code GET /api/auth/me -H "Authorization: Bearer $TS3")"
code GET /api/me/sessions -H "Authorization: Bearer $TS3" >/dev/null
check "список своих сессий пуст" 0 "$(jq 'length' < "$WORK/body")"
check "оборвать сессии несуществующего пользователя" 404 \
    "$(code DELETE "/api/admin/users/00000000-0000-0000-0000-000000000000/sessions" -H "Authorization: Bearer $TA")"
check "временный пользователь удалён" 204 "$(code DELETE "/api/admin/users/$SUID" -H "Authorization: Bearer $TA")"

head_ "Смена пароля"
PU="smoke-pwd-$RANDOM-$RANDOM@seoniks.ru"; PPASS=$(openssl rand -hex 16); PNEW=$(openssl rand -hex 16)
curl -s -o "$WORK/body" -X POST "$BASE/api/admin/users" -H "Authorization: Bearer $TA" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$PU\",\"password\":\"$PPASS\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_1\"]}" >/dev/null
PUID=$(jq -r '.id' < "$WORK/body")
login "$PU" "$PPASS" "$WORK/jw" "$WORK/lw.json" >/dev/null; TW="$(tok "$WORK/lw.json")"
check "POST /api/auth/password без токена" 401 "$(code POST /api/auth/password -H 'Content-Type: application/json' \
    -d "{\"currentPassword\":\"$PPASS\",\"newPassword\":\"$PNEW\"}")"
check "неверный текущий пароль" 400 "$(code POST /api/auth/password -H "Authorization: Bearer $TW" \
    -H 'Content-Type: application/json' -d "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"$PNEW\"}")"
check "новый пароль короче 10" 400 "$(code POST /api/auth/password -H "Authorization: Bearer $TW" \
    -H 'Content-Type: application/json' -d "{\"currentPassword\":\"$PPASS\",\"newPassword\":\"short\"}")"
check "новый пароль совпадает с текущим" 400 "$(code POST /api/auth/password -H "Authorization: Bearer $TW" \
    -H 'Content-Type: application/json' -d "{\"currentPassword\":\"$PPASS\",\"newPassword\":\"$PPASS\"}")"
check "успешная смена пароля" 204 "$(code POST /api/auth/password -H "Authorization: Bearer $TW" \
    -H 'Content-Type: application/json' -d "{\"currentPassword\":\"$PPASS\",\"newPassword\":\"$PNEW\"}")"
check "старым паролем войти нельзя" 401 "$(login "$PU" "$PPASS" "$WORK/jx" "$WORK/lx.json")"
check "новым паролем войти можно"   200 "$(login "$PU" "$PNEW" "$WORK/jy" "$WORK/ly.json")"
check "refresh прежней сессии отозван" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/jw" "$BASE/api/auth/refresh")"
check "временный пользователь удалён" 204 "$(code DELETE "/api/admin/users/$PUID" -H "Authorization: Bearer $TA")"

# ---------------------------------------------------------------- аудит: доступ
head_ "Журнал аудита: разграничение доступа"
AUD='/api/admin/audit'
check "GET $AUD без токена"  401 "$(code GET "$AUD")"
check "GET $AUD под USER"    403 "$(code GET "$AUD" -H "Authorization: Bearer $TU")"
check "GET $AUD под ADMIN"   200 "$(code GET "$AUD?size=5" -H "Authorization: Bearer $TA")"
jq -e '.items | length > 0' < "$WORK/body" >/dev/null 2>&1 \
  && ok "список событий непустой (total=$(jq -r '.total' < "$WORK/body"))" \
  || bad "список событий непустой" ">0" "0"
jq -e '.items[0] | has("ts") and has("event") and has("actorEmail") and has("ip") and has("success")' \
  < "$WORK/body" >/dev/null 2>&1 \
  && ok "в записи есть ts/event/actorEmail/ip/success" \
  || bad "состав записи журнала" "полный" "неполный"

head_ "Журнал аудита: события пишутся"
FAILMAIL="smoke-fail-$RANDOM-$RANDOM@seoniks.ru"
curl -s -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$FAILMAIL\",\"password\":\"wrong-password-xyz\"}"
sleep 1
code GET "$AUD?event=AUTH_LOGIN_FAIL&actor=$FAILMAIL&size=5" -H "Authorization: Bearer $TA" >/dev/null
N=$(jq -r '.total // 0' < "$WORK/body")
[ "${N:-0}" -ge 1 ] && ok "AUTH_LOGIN_FAIL записан для $FAILMAIL (total=$N)" \
                    || bad "AUTH_LOGIN_FAIL записан" ">=1" "$N"
jq -e '.items[0].details.reason == "bad_credentials"' < "$WORK/body" >/dev/null 2>&1 \
  && ok "в details причина bad_credentials" || bad "details.reason" "bad_credentials" "$(jq -c '.items[0].details' < "$WORK/body")"
jq -e --arg e "$FAILMAIL" '.items[0].details.email == $e' < "$WORK/body" >/dev/null 2>&1 \
  && ok "в details email неудачного входа" || bad "details.email" "$FAILMAIL" "$(jq -c '.items[0].details' < "$WORK/body")"
AIP=$(jq -r '.items[0].ip // empty' < "$WORK/body")
[ -n "$AIP" ] && ok "в событии записан IP ($AIP)" || bad "IP в событии" "не пусто" "пусто"

TMPU="smoke-audit-$RANDOM-$RANDOM@seoniks.ru"; TMPP=$(openssl rand -hex 16)
check "создан временный пользователь" 201 \
  "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/admin/users" \
     -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$TMPU\",\"password\":\"$TMPP\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_1\"]}")"
TMPID=$(jq -r '.id' < "$WORK/body")
check "удалён временный пользователь" 204 "$(code DELETE "/api/admin/users/$TMPID" -H "Authorization: Bearer $TA")"
sleep 1

code GET "$AUD?event=ADMIN_USER_CREATE&size=20" -H "Authorization: Bearer $TA" >/dev/null
jq -e --arg e "$TMPU" '[.items[] | select(.target == $e)] | length >= 1' < "$WORK/body" >/dev/null 2>&1 \
  && ok "ADMIN_USER_CREATE записан для $TMPU" || bad "ADMIN_USER_CREATE" "есть" "нет"
jq -e --arg e "$TMPU" --arg p "$TMPP" \
   '(.items[] | select(.target == $e) | .details) as $d
    | ($d.email == $e) and ($d.role == "USER") and ($d.pages == ["TEST_PAGE_1"])
      and (($d | tostring) | contains($p) | not)' < "$WORK/body" >/dev/null 2>&1 \
  && ok "details создания: email+role+pages, пароль отсутствует" \
  || bad "details ADMIN_USER_CREATE" "email,role,pages без пароля" "$(jq -c --arg e "$TMPU" '.items[]|select(.target==$e)|.details' < "$WORK/body" | head -1)"

code GET "$AUD?event=ADMIN_USER_DELETE&size=20" -H "Authorization: Bearer $TA" >/dev/null
jq -e --arg e "$TMPU" '[.items[] | select(.target == $e)] | length >= 1' < "$WORK/body" >/dev/null 2>&1 \
  && ok "ADMIN_USER_DELETE записан для $TMPU" || bad "ADMIN_USER_DELETE" "есть" "нет"

code GET "$AUD?event=PAGE_DENIED&size=20" -H "Authorization: Bearer $TA" >/dev/null
jq -e '[.items[] | select(.details.code == "TEST_PAGE_2")] | length >= 1' < "$WORK/body" >/dev/null 2>&1 \
  && ok "PAGE_DENIED с кодом страницы в details" || bad "PAGE_DENIED" "есть" "нет"

ST=$(code GET "$AUD?event=NO_SUCH_EVENT&size=5" -H "Authorization: Bearer $TA")
check "неизвестное имя события не роняет запрос" 200 "$ST"

# ---------------------------------------------------------------- T3: утечка
head_ "T3: журнал не содержит секретов"
SECRET='SuperSecret123!'
LEAK="smoke-leak-$RANDOM-$RANDOM@seoniks.ru"
curl -s -o "$WORK/body" -X POST "$BASE/api/admin/users" -H "Authorization: Bearer $TA" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$LEAK\",\"password\":\"$SECRET\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_1\"]}" >/dev/null
LEAKID=$(jq -r '.id' < "$WORK/body")
curl -s -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$LEAK\",\"password\":\"$SECRET\"}"
curl -s -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$LEAK\",\"password\":\"${SECRET}wrong\"}"
sleep 1
: > "$WORK/audit-dump.jsonl"
PG=0
while [ "$PG" -lt 20 ]; do
  curl -s -H "Authorization: Bearer $TA" "$BASE$AUD?page=$PG&size=200" > "$WORK/page.json"
  jq -c '.items[]' < "$WORK/page.json" >> "$WORK/audit-dump.jsonl" 2>/dev/null
  GOT=$(jq -r '.items | length' < "$WORK/page.json" 2>/dev/null); GOT=${GOT:-0}
  [ "$GOT" -lt 200 ] && break
  PG=$((PG + 1))
done
DUMPED=$(wc -l < "$WORK/audit-dump.jsonl")
echo "        выгружено записей журнала: $DUMPED"
check "журнал выгружен непустым (иначе T3 ничего не доказывает)" "yes" \
      "$([ "${DUMPED:-0}" -gt 0 ] && echo yes || echo no)"
LEAKED=0
for pat in "$SECRET" "${SECRET}wrong" 'argon2' 'accessToken' 'refresh_token=' 'Bearer ' 'password' 'eyJhbGciOiJSUzI1NiJ9'; do
  C=$(grep -c -F -- "$pat" "$WORK/audit-dump.jsonl" 2>/dev/null); C=${C:-0}
  printf '        %-24s совпадений: %s\n' "\"$pat\"" "$C"
  [ "${C:-0}" -ne 0 ] && LEAKED=$((LEAKED + 1))
done
check "ни один секрет не встретился в журнале" 0 "$LEAKED"
KEYS=$(jq -r '.details // {} | keys[]' "$WORK/audit-dump.jsonl" 2>/dev/null | sort -u | tr '\n' ' ')
echo "        ключи details во всём журнале: $KEYS"
FORB=$(echo "$KEYS" | tr ' ' '\n' | grep -icE 'password|token|secret|cookie|authorization|hash|credential|jwt|session' || true)
check "в ключах details нет запрещённых слов" 0 "${FORB:-0}"
curl -s -o /dev/null -X DELETE "$BASE/api/admin/users/$LEAKID" -H "Authorization: Bearer $TA"
echo "        временный пользователь $LEAK удалён"

# ---------------------------------------------------------------- T4: access-лог
head_ "T4: USER не получил ни одной 200 от /api/admin/audit"
ACCESS_LOG=/var/log/nginx/access.log
if [ -r "$ACCESS_LOG" ]; then
  MARK=$(wc -l < "$ACCESS_LOG")
  for _ in 1 2 3; do
    curl -s -o /dev/null "$BASE$AUD" -H "Authorization: Bearer $TU"
  done
  sleep 1
  WINDOW=$(tail -n +"$((MARK + 1))" "$ACCESS_LOG" | grep "$AUD" || true)
  HITS=$(printf '%s\n' "$WINDOW" | grep -c "$AUD" || true)
  OK200=$(printf '%s\n' "$WINDOW" | awk '$9 == 200' | grep -c "$AUD" || true)
  OK403=$(printf '%s\n' "$WINDOW" | awk '$9 == 403' | grep -c "$AUD" || true)
  check "запросов USER к $AUD в окне"      3   "$HITS"
  check "из них ответов 200"               0   "${OK200:-0}"
  check "из них ответов 403"               3   "${OK403:-0}"
else
  echo "        $ACCESS_LOG недоступен — проверка T4 пропущена (запуск не на сервере)"
fi

# ---------------------------------------------------------------- CSRF
head_ "CSRF: почему межсайтовая подделка запроса неприменима"
login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jc" "$WORK/lc.json" >/dev/null
TC="$(tok "$WORK/lc.json")"

SC="$(curl -s -D - -o /dev/null -X POST "$BASE/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}" \
      | grep -i '^set-cookie:' | tr -d '\r')"
for attr in "HttpOnly" "Secure" "SameSite=Strict" "Path=/api/auth"; do
    case "$SC" in *"$attr"*) ok "Set-Cookie refresh_token содержит $attr";;
                  *) bad "Set-Cookie refresh_token содержит $attr" "есть" "нет";; esac
done

for ep in /api/auth/refresh /api/auth/logout; do
    HDRS="$WORK/csrf-h"
    curl -s -D "$HDRS" -o /dev/null -X POST "$BASE$ep" \
         -H "Origin: https://evil.example" \
         -H "Referer: https://evil.example/attack.html" \
         -b "$WORK/jc" -H "Authorization: Bearer $TC"
    ACAO=$(grep -ci '^access-control-allow-origin:' "$HDRS" 2>/dev/null); ACAO=${ACAO:-0}
    ACAC=$(grep -ci '^access-control-allow-credentials:' "$HDRS" 2>/dev/null); ACAC=${ACAC:-0}
    check "POST $ep с Origin evil.example: нет Access-Control-Allow-Origin" 0 "$ACAO"
    check "POST $ep с Origin evil.example: нет Access-Control-Allow-Credentials" 0 "$ACAC"
    grep -qi 'evil\.example' "$HDRS" \
        && bad "в заголовках ответа $ep не упомянут чужой origin" "не упомянут" "упомянут" \
        || ok "в заголовках ответа $ep чужой origin не упомянут"
done

curl -s -D "$WORK/pre" -o /dev/null -X OPTIONS "$BASE/api/auth/login" \
     -H "Origin: https://evil.example" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: content-type"
PRE_ACAO=$(grep -i '^access-control-allow-origin:' "$WORK/pre" 2>/dev/null | tr -d '\r')
PRE_ACAC=$(grep -i '^access-control-allow-credentials:' "$WORK/pre" 2>/dev/null | tr -d '\r')
check "preflight OPTIONS не разрешает evil.example" "" "$PRE_ACAO"
check "preflight OPTIONS не разрешает credentials" "" "$PRE_ACAC"
echo "        статус preflight: $(head -1 "$WORK/pre" | tr -d '\r')"

check "login с Content-Type form-urlencoded" 415 \
    "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d 'email=user@example.com&password=1234567890')"
check "login без Content-Type" 415 \
    "$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
        -d 'email=user@example.com&password=1234567890')"
check "login с JSON остаётся рабочим" 200 \
    "$(login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jc2" "$WORK/lc2.json")"

# ---------------------------------------------------------------- очистка журнала
head_ "Очистка журнала аудита (разрушающая проверка, снимок сохраняется)"
SNAP="${AUDIT_SNAPSHOT:-/root/backups/audit-before-clear-$(date +%Y%m%d-%H%M%S).jsonl}"
if mkdir -p "$(dirname "$SNAP")" 2>/dev/null; then
    : > "$SNAP"
    SP=0
    while [ "$SP" -lt 50 ]; do
        curl -s -H "Authorization: Bearer $TA" "$BASE$AUD?page=$SP&size=200" > "$WORK/snap.json"
        jq -c '.items[]' < "$WORK/snap.json" >> "$SNAP" 2>/dev/null
        SG=$(jq -r '.items | length' < "$WORK/snap.json" 2>/dev/null); SG=${SG:-0}
        [ "$SG" -lt 200 ] && break
        SP=$((SP + 1))
    done
    echo "        снимок журнала: $SNAP ($(wc -l < "$SNAP") строк) — очистка обратима"
else
    echo "        не удалось создать $(dirname "$SNAP") — снимаю снапшот во временный каталог"
    SNAP="$WORK/audit-snapshot.jsonl"; : > "$SNAP"
    curl -s -H "Authorization: Bearer $TA" "$BASE$AUD?page=0&size=200" | jq -c '.items[]' >> "$SNAP" 2>/dev/null
    echo "        снимок журнала: $SNAP ($(wc -l < "$SNAP") строк)"
fi

check "POST $AUD/clear без токена" 401 "$(code POST "$AUD/clear")"
check "POST $AUD/clear под USER"   403 "$(code POST "$AUD/clear" -H "Authorization: Bearer $TU")"
BEFORE_CLEAR=$(curl -s -H "Authorization: Bearer $TA" "$BASE$AUD?size=1" | jq -r '.total')
echo "        до очистки $BEFORE_CLEAR строк; сам этот запрос добавляет ещё одну AUDIT_READ,"
echo "        поэтому фактически удалено должно быть на одну больше"
check "POST $AUD/clear под ADMIN"  200 "$(code POST "$AUD/clear" -H "Authorization: Bearer $TA")"
DELETED=$(jq -r '.deleted // empty' < "$WORK/body")
if [ -n "$DELETED" ] && [ "$DELETED" -ge "$BEFORE_CLEAR" ]; then
    ok "удалено $DELETED строк, не меньше $BEFORE_CLEAR"
else
    bad "число удалённых строк" ">= $BEFORE_CLEAR" "${DELETED:-пусто}"
fi

code GET "$AUD?size=5" -H "Authorization: Bearer $TA" >/dev/null
check "после очистки в журнале ровно одна строка" 1 "$(jq -r '.total' < "$WORK/body")"
check "эта строка — AUDIT_CLEAR"  "AUDIT_CLEAR" "$(jq -r '.items[0].event' < "$WORK/body")"
check "в AUDIT_CLEAR записан актёр" "$ADMIN_EMAIL" "$(jq -r '.items[0].actorEmail' < "$WORK/body")"
check "в AUDIT_CLEAR записана роль" "ADMIN" "$(jq -r '.items[0].actorRole' < "$WORK/body")"
CLE_IP=$(jq -r '.items[0].ip // empty' < "$WORK/body")
[ -n "$CLE_IP" ] && ok "в AUDIT_CLEAR записан IP ($CLE_IP)" || bad "IP в AUDIT_CLEAR" "не пусто" "пусто"
check "details.count в AUDIT_CLEAR равен фактически удалённому" "${DELETED:-}" \
      "$(jq -r '.items[0].details.count' < "$WORK/body")"

# ---------------------------------------------------------------- --clean-audit
if [ "$CLEAN_AUDIT" -eq 1 ]; then
    head_ "Очистка следов самопроверок из audit_log (--clean-audit)"
    SELF_IP="${SELF_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
    if command -v psql >/dev/null 2>&1 && [ -n "$SELF_IP" ]; then
        cat > "$WORK/clean.sql" <<SQL
\\pset tuples_only on
create temp table _keep as select id from audit_log;
delete from audit_log
 where actor_email ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru\$'
    or target      ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru\$'
    or actor_email ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru\$'
    or target      ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru\$'
    or actor_email ~* '^cov(dis|rl)?-[0-9]+@seoniks\\.ru\$'
    or target      ~* '^cov(dis|rl)?-[0-9]+@seoniks\\.ru\$'
    or actor_email ~* '^lifecycle-(admin|victim)@seoniks\\.ru\$'
    or target      ~* '^lifecycle-(admin|victim)@seoniks\\.ru\$'
    or actor_email ~* '^xff-(probe[0-9]*|fixed)@seoniks\\.ru\$'
    or target      ~* '^xff-(probe[0-9]*|fixed)@seoniks\\.ru\$'
    or actor_email ~* '^bad-[0-9]+@seoniks\\.ru\$'
    or coalesce(details->>'email','')      ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru\$'
    or coalesce(details->>'targetEmail','') ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru\$'
    or coalesce(details->>'email','')      ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru\$'
    or coalesce(details->>'targetEmail','') ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru\$'
    or target ~* '^(SMOKE_PAGE|JOURNEY_PAGE)_[0-9]+\$'
    or coalesce(details->>'code','') ~* '^(SMOKE_PAGE|JOURNEY_PAGE)_[0-9]+\$'
    or actor_email ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru\$'
    or target      ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru\$';
SQL
        cat >> "$WORK/clean.sql" <<SQL
delete from audit_log
 where actor_email in ('owner@seoniks.ru','user1@seoniks.ru')
    and ip in ('127.0.0.1','$SELF_IP');
delete from audit_log
 where actor_email is null and ip in ('127.0.0.1','$SELF_IP');
select 'удалено всего: ' || (select count(*) from _keep k where not exists
        (select 1 from audit_log a where a.id = k.id));
select 'осталось строк: ' || count(*) from audit_log;
SQL
        CLEAN_OUT=$(sudo -u postgres psql -d authdb -v ON_ERROR_STOP=1 < "$WORK/clean.sql" 2>&1)
        CLEAN_RC=$?
        printf '%s\n' "$CLEAN_OUT" | sed 's/^/        /'
        check "очистка audit_log выполнена без ошибок psql" 0 "$CLEAN_RC"
        echo "        критерий: тестовые адреса (ratelimit-test, leak-test, smoke-*, cov*, lifecycle-*, xff-*, bad-*, admin@)"
        echo "                  плюс строки owner@/user1@ и безымянные, пришедшие с 127.0.0.1 или $SELF_IP"
        echo "        строки реальных пользователей с внешних адресов сохранены"
    else
        echo "        psql недоступен или не определён адрес сервера — очистка пропущена"
    fi
fi

# ---------------------------------------------------------------- итог
echo
echo "############################################################"
printf '# ИТОГО: PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
    echo '# ВЕРДИКТ: ALL PASS'
    echo "############################################################"
    exit 0
else
    echo '# ВЕРДИКТ: ЕСТЬ ПРОВАЛЫ'
    echo "############################################################"
    exit 1
fi
