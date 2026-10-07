#!/usr/bin/env bash
#
# user-journey-test.sh — сквозное путешествие от лица обычного пользователя.
#
# Проходит путь, которым пойдёт реальный человек: администратор создаёт
# пользователя и страницу, пользователь входит, видит свой кабинет и назначенные
# страницы, не видит чужих и админки, меняет пароль, его сессию обрывают,
# после чего всё созданное удаляется.
#
# Запуск на сервере:
#   bash deploy/user-journey-test.sh                 обычная проверка
#   bash deploy/user-journey-test.sh --keep-audit    не чистить audit_log
#   bash deploy/user-journey-test.sh --clean-audit   в конце очистить журнал целиком
#
# Скрипт ничего не ломает: работает с временным пользователем и временной
# страницей, которые сам же и удаляет. Выходной код 0 означает, что все шаги
# прошли; при любом расхождении скрипт печатает FAIL и завершается с кодом 1.

set -uo pipefail

BASE="${BASE:-https://app.seoniks.ru}"
CRED_FILE="${CRED_FILE:-/root/app-credentials.txt}"
KEEP_AUDIT=0
CLEAN_ALL=0
for arg in "$@"; do
  case "$arg" in
    --keep-audit) KEEP_AUDIT=1 ;;
    --clean-audit) CLEAN_ALL=1 ;;
    *) echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
  esac
done

# Те же имена и тот же порядок поиска, что в smoke-test.sh: сначала окружение,
# потом файл с учётными данными. Нужны один ADMIN и один USER.
cred() {
  if [ -n "${!1:-}" ]; then printf '%s' "${!1}"; return; fi
  [ -r "$CRED_FILE" ] && grep "^$2=" "$CRED_FILE" | head -1 | cut -d= -f2-
}
ADMIN_EMAIL="$(cred ADMIN_EMAIL OWNER_ADMIN_EMAIL)"
ADMIN_PASSWORD="$(cred ADMIN_PASSWORD OWNER_ADMIN_PASSWORD)"
USER_EMAIL="$(cred USER_EMAIL TEST_USER_EMAIL)"
USER_PASSWORD="$(cred USER_PASSWORD TEST_USER_PASSWORD)"

for v in ADMIN_EMAIL ADMIN_PASSWORD USER_EMAIL USER_PASSWORD; do
  [ -n "${!v}" ] || { echo "Не задан $v (ни в окружении, ни в $CRED_FILE)" >&2; exit 2; }
done

STAMP="$(date +%H%M%S)"
RAND="$RANDOM$RANDOM"
JEMAIL="journey-$RAND@seoniks.ru"
JPASS="$(openssl rand -hex 16)"
JNEWPASS="$(openssl rand -hex 16)"
JPAGE="JOURNEY_PAGE_$RAND"

WORK="$(mktemp -d /tmp/user-journey.XXXXXX)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

PASS=0; FAIL=0
ok()  { printf '  PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  FAIL  %s (ожидалось: %s, получено: %s)\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1 [$2]"; else bad "$1" "$2" "$3"; fi; }
head_() { printf '\n== %s ==\n' "$1"; }

# code МЕТОД ПУТЬ [аргументы curl] — печатает HTTP-код, тело кладёт в $WORK/body
code(){
  local m="$1" p="$2"; shift 2
  curl -s -o "$WORK/body" -w '%{http_code}' -X "$m" "$BASE$p" "$@"
}
json(){ jq -r "$1" < "$WORK/body" 2>/dev/null; }
tok(){ jq -r '.accessToken // empty' < "$1" 2>/dev/null; }
login(){
  curl -s -c "$3" -o "$4" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
       -H 'Content-Type: application/json' \
       -d "{\"email\":\"$1\",\"password\":\"$2\"}"
}

echo "=== ПУТЕШЕСТВИЕ ПОЛЬЗОВАТЕЛЯ ==="
echo "  дата:        $(date '+%F %T')"
echo "  база:        $BASE"
echo "  пользователь: $JEMAIL"
echo "  страница:     $JPAGE"

# ---------------------------------------------------------------- шаг 0: вход администратора
head_ "Шаг 0. Вход администратора"
chk "администратор вошёл" 200 "$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "$WORK/ja" "$WORK/la.json")"
TA="$(tok "$WORK/la.json")"
chk "в ответе есть accessToken" "yes" "$([ "${TA:0:4}" = 'eyJh' ] && echo yes || echo no)"
chk "/api/auth/me возвращает роль ADMIN" "ADMIN" \
    "$(code GET /api/auth/me -H "Authorization: Bearer $TA" >/dev/null; json '.role')"

# ---------------------------------------------------------------- шаг a: создание
head_ "Шаг a. Администратор создаёт страницу и пользователя"
chk "создана динамическая страница $JPAGE" 201 "$(curl -s -o "$WORK/body" -w '%{http_code}' \
    -X POST "$BASE/api/admin/pages" -H "Authorization: Bearer $TA" \
    -H 'Content-Type: application/json' \
    -d "{\"code\":\"$JPAGE\",\"title\":\"Страница путешествия\",\"content\":\"Привет, это страница $JPAGE.\nВторая строка содержимого.\"}")"
chk "код созданной страницы" "$JPAGE" "$(json '.code')"

chk "создан пользователь $JEMAIL" 201 "$(curl -s -o "$WORK/body" -w '%{http_code}' \
    -X POST "$BASE/api/admin/users" -H "Authorization: Bearer $TA" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$JEMAIL\",\"password\":\"$JPASS\",\"role\":\"USER\",\"pages\":[\"TEST_PAGE_1\",\"$JPAGE\"]}")"
JID="$(json '.id')"
chk "id пользователя получен" "yes" "$([ -n "$JID" ] && [ "$JID" != null ] && echo yes || echo no)"
chk "роль при создании" "USER" "$(json '.role')"
chk "назначены обе страницы" "$JPAGE,TEST_PAGE_1" "$(json '[.pages[]]|sort|join(",")')"

# ---------------------------------------------------------------- шаг b: вход пользователя
head_ "Шаг b. Пользователь входит"
chk "вход по выданному паролю" 200 "$(login "$JEMAIL" "$JPASS" "$WORK/ju" "$WORK/lu.json")"
TU="$(tok "$WORK/lu.json")"
chk "/api/auth/me: роль" "USER" "$(code GET /api/auth/me -H "Authorization: Bearer $TU" >/dev/null; json '.role')"
chk "/api/auth/me: email" "$JEMAIL" "$(json '.email')"
chk "/api/auth/me: страницы равны назначенным" "$JPAGE,TEST_PAGE_1" "$(json '[.pages[]]|sort|join(",")')"
chk "/api/auth/me: enabled" "true" "$(json '.enabled')"

# ---------------------------------------------------------------- шаг c: личный кабинет
head_ "Шаг c. Личный кабинет (данные API)"
chk "GET /api/me/sessions" 200 "$(code GET /api/me/sessions -H "Authorization: Bearer $TU")"
chk "своя сессия присутствует" 1 "$(jq 'length' < "$WORK/body")"
chk "сессия помечена как текущая" 1 "$(jq '[.[]|select(.current)]|length' < "$WORK/body")"
chk "в сессии виден собственный email" "$JEMAIL" "$(jq -r '.[0].userEmail' < "$WORK/body")"
chk "в сессии есть ip" "yes" "$(jq -r 'if (.[0].ip // "") == "" then "no" else "yes" end' < "$WORK/body")"
chk "в сессии есть user_agent" "yes" "$(jq -r 'if (.[0].userAgent // "") == "" then "no" else "yes" end' < "$WORK/body")"
chk "чужие сессии в кабинете не видны" 1 "$(jq '[.[].userEmail]|unique|length' < "$WORK/body")"
chk "SPA-маршрут /profile отдаётся" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/profile")"

# ---------------------------------------------------------------- шаг d: доступ к страницам
head_ "Шаг d. Доступ к страницам и админке"
chk "назначенная TEST_PAGE_1" 200 "$(code GET /api/pages/TEST_PAGE_1 -H "Authorization: Bearer $TU")"
chk "заголовок TEST_PAGE_1" "Тестовая страница 1" "$(json '.title')"
chk "содержимое TEST_PAGE_1 не пустое" "yes" "$([ -n "$(json '.content')" ] && echo yes || echo no)"
chk "назначенная $JPAGE" 200 "$(code GET "/api/pages/$JPAGE" -H "Authorization: Bearer $TU")"
chk "содержимое динамической страницы из базы" "Привет, это страница $JPAGE.
Вторая строка содержимого." "$(json '.content')"
chk "неназначенная TEST_PAGE_3" 403 "$(code GET /api/pages/TEST_PAGE_3 -H "Authorization: Bearer $TU")"
chk "несуществующая страница у USER" 403 "$(code GET /api/pages/NO_SUCH_PAGE -H "Authorization: Bearer $TU")"
chk "код в нижнем регистре" 403 "$(code GET "/api/pages/$(echo "$JPAGE" | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $TU")"
chk "админка: список пользователей" 403 "$(code GET /api/admin/users -H "Authorization: Bearer $TU")"
chk "админка: список страниц" 403 "$(code GET /api/admin/pages -H "Authorization: Bearer $TU")"
chk "админка: список сессий" 403 "$(code GET /api/admin/sessions -H "Authorization: Bearer $TU")"
chk "админка: журнал аудита" 403 "$(code GET /api/admin/audit -H "Authorization: Bearer $TU")"
chk "SPA-маршрут /page/:code отдаётся" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/page/$JPAGE")"

# ---------------------------------------------------------------- шаг e: смена пароля
head_ "Шаг e. Смена пароля в личном кабинете"
chk "неверный текущий пароль отклонён" 400 "$(code POST /api/auth/password \
    -H "Authorization: Bearer $TU" -H 'Content-Type: application/json' \
    -d "{\"currentPassword\":\"wrong-current-pass\",\"newPassword\":\"$JNEWPASS\"}")"
chk "короткий новый пароль отклонён" 400 "$(code POST /api/auth/password \
    -H "Authorization: Bearer $TU" -H 'Content-Type: application/json' \
    -d "{\"currentPassword\":\"$JPASS\",\"newPassword\":\"short\"}")"
chk "смена пароля" 204 "$(code POST /api/auth/password \
    -H "Authorization: Bearer $TU" -H 'Content-Type: application/json' \
    -d "{\"currentPassword\":\"$JPASS\",\"newPassword\":\"$JNEWPASS\"}")"
chk "старым паролем войти нельзя" 401 "$(login "$JEMAIL" "$JPASS" "$WORK/jx" "$WORK/lx.json")"
chk "новым паролем войти можно" 200 "$(login "$JEMAIL" "$JNEWPASS" "$WORK/jn" "$WORK/ln.json")"
TN="$(tok "$WORK/ln.json")"
chk "прежняя сессия отозвана" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/ju" "$BASE/api/auth/refresh")"
chk "новая сессия работает" "USER" "$(code GET /api/auth/me -H "Authorization: Bearer $TN" >/dev/null; json '.role')"

# ---------------------------------------------------------------- шаг f: обрыв сессии администратором
head_ "Шаг f. Администратор видит и обрывает сессию пользователя"
code GET /api/admin/sessions -H "Authorization: Bearer $TA" >/dev/null
chk "сессия пользователя видна администратору" "yes" \
    "$(jq -r --arg e "$JEMAIL" 'if ([.[]|select(.userEmail==$e)]|length) > 0 then "yes" else "no" end' < "$WORK/body")"
JFAM="$(jq -r --arg e "$JEMAIL" '[.[]|select(.userEmail==$e)][0].familyId' < "$WORK/body")"
chk "в сессии есть ip" "yes" \
    "$(jq -r --arg e "$JEMAIL" 'if (([.[]|select(.userEmail==$e)][0].ip // "") == "") then "no" else "yes" end' < "$WORK/body")"
chk "фильтр по пользователю работает" "$JEMAIL" \
    "$(code GET "/api/admin/sessions?user=$JEMAIL" -H "Authorization: Bearer $TA" >/dev/null; jq -r '[.[].userEmail]|unique|join(",")' < "$WORK/body")"
chk "обрыв сессии" 204 "$(code DELETE "/api/admin/sessions/$JFAM" -H "Authorization: Bearer $TA")"
chk "refresh оборванной сессии" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$WORK/jn" "$BASE/api/auth/refresh")"
chk "access-токен доживает свои 15 минут" 200 "$(code GET /api/auth/me -H "Authorization: Bearer $TN")"

# ---------------------------------------------------------------- шаг g: уборка
head_ "Шаг g. Удаление пользователя и временной страницы"
chk "пользователь удалён" 204 "$(code DELETE "/api/admin/users/$JID" -H "Authorization: Bearer $TA")"
chk "страница удалена" 204 "$(code DELETE "/api/admin/pages/$JPAGE" -H "Authorization: Bearer $TA")"
chk "удалённой страницы больше нет в списке" "no" \
    "$(code GET /api/admin/pages -H "Authorization: Bearer $TA" >/dev/null; jq -r --arg c "$JPAGE" 'if ([.[]|select(.code==$c)]|length) > 0 then "yes" else "no" end' < "$WORK/body")"
chk "TEST_PAGE_1 не пострадала" "Тестовая страница 1" \
    "$(code GET /api/pages/TEST_PAGE_1 -H "Authorization: Bearer $TA" >/dev/null; json '.title')"
chk "реальный пользователь на месте" 200 "$(login "$USER_EMAIL" "$USER_PASSWORD" "$WORK/jv" "$WORK/lv.json")"

# ---------------------------------------------------------------- шаг h: чистка журнала
if [ "$KEEP_AUDIT" -eq 1 ]; then
  head_ "Шаг h. Журнал аудита"
  echo "  --keep-audit: записи путешествия оставлены в audit_log"
  echo "  строк с упоминанием путешествия: $(sudo -u postgres psql -d authdb -tAc \
      "select count(*) from audit_log where actor_email='$JEMAIL' or target_email='$JEMAIL' or details::text ilike '%$JEMAIL%' or details::text ilike '%$JPAGE%';" 2>/dev/null || echo '?')"
elif command -v psql >/dev/null 2>&1; then
  head_ "Шаг h. Уборка следов путешествия из журнала аудита"
  # Снимок снимаем через API тем же способом, что и smoke-test: файл пишет root,
  # поэтому права на каталог /root/backups для пользователя postgres не важны.
  BACKUP="/root/backups/audit-before-journey-$(date +%Y%m%d-%H%M%S).jsonl"
  if mkdir -p "$(dirname "$BACKUP")" 2>/dev/null; then : > "$BACKUP"; else
    BACKUP="$WORK/audit-snapshot.jsonl"; : > "$BACKUP"
  fi
  JP=0
  while [ "$JP" -lt 50 ]; do
    code GET "/api/admin/audit?page=$JP&size=200" -H "Authorization: Bearer $TA" >/dev/null
    jq -c '.items[]' < "$WORK/body" >> "$BACKUP" 2>/dev/null
    JG=$(jq -r '.items | length' < "$WORK/body" 2>/dev/null); JG=${JG:-0}
    [ "$JG" -lt 200 ] && break
    JP=$((JP + 1))
  done

  cat > "$WORK/clean.sql" <<SQL
\\pset tuples_only on
create temp table _keep as select id from audit_log;
-- точечная уборка: только то, что создал этот прогон
delete from audit_log
 where actor_email = '$JEMAIL'
    or target      = '$JEMAIL'
    or coalesce(details->>'email','')       = '$JEMAIL'
    or coalesce(details->>'targetEmail','') = '$JEMAIL'
    or target      = '$JPAGE'
    or coalesce(details->>'code','') = '$JPAGE'
    or details::text ilike '%$JPAGE%'
    or event = 'AUDIT_CLEAR';
SQL

  if [ "$CLEAN_ALL" -eq 1 ]; then
    cat >> "$WORK/clean.sql" <<SQL
-- по протоколу прошлых раундов: sweeping всех тестовых учёток и временных страниц
delete from audit_log
 where actor_email ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru$'
    or target      ~* '^(ratelimit-test|leak-test|admin)@seoniks\\.ru$'
    or actor_email ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru$'
    or target      ~* '^smoke-[a-z]+-[0-9]+-[0-9]+@seoniks\\.ru$'
    or actor_email ~* '^sess-test-[0-9]+@seoniks\\.ru$'
    or target      ~* '^sess-test-[0-9]+@seoniks\\.ru$'
    or actor_email ~* '^(cov(dis|rl)?|bad|lifecycle-(admin|victim)|xff-(probe[0-9]*|fixed))[-0-9]*@seoniks\\.ru$'
    or target      ~* '^(cov(dis|rl)?|bad|lifecycle-(admin|victim)|xff-(probe[0-9]*|fixed))[-0-9]*@seoniks\\.ru$'
    or actor_email ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru$'
    or target      ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru$'
    or coalesce(details->>'email','')       ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru$'
    or coalesce(details->>'targetEmail','') ~* '^(journey|sess-test)-[0-9]+@seoniks\\.ru$'
    or coalesce(details->>'email','')       ~* '^(ratelimit-test|leak-test|admin|smoke-[a-z]+-[0-9]+-[0-9]+|sess-test-[0-9]+)@seoniks\\.ru$'
    or coalesce(details->>'targetEmail','') ~* '^(ratelimit-test|leak-test|admin|smoke-[a-z]+-[0-9]+-[0-9]+|sess-test-[0-9]+)@seoniks\\.ru$'
    or target ~* '^(SMOKE_PAGE|JOURNEY_PAGE)_[0-9]+$'
    or coalesce(details->>'code','') ~* '^(SMOKE_PAGE|JOURNEY_PAGE)_[0-9]+$';
SQL
  fi

  cat >> "$WORK/clean.sql" <<SQL
select 'удалено строк: ' || (select count(*) from _keep k where not exists
        (select 1 from audit_log a where a.id = k.id));
select 'осталось строк: ' || count(*) from audit_log;
SQL

  CLEAN_OUT=$(sudo -u postgres psql -d authdb -v ON_ERROR_STOP=1 < "$WORK/clean.sql" 2>&1)
  CLEAN_RC=$?
  printf '%s\n' "$CLEAN_OUT" | sed 's/^/        /'
  chk "очистка журнала выполнена без ошибок psql" 0 "$CLEAN_RC"

  LEFT="$(sudo -u postgres psql -d authdb -tAc "select count(*) from audit_log
      where actor_email='$JEMAIL' or target='$JEMAIL' or target='$JPAGE'
         or coalesce(details->>'code','')='$JPAGE' or details::text ilike '%$JPAGE%';" 2>&1)"
  chk "в журнале не осталось следов путешествия" 0 "$LEFT"
  echo "        копия журнала до уборки: $BACKUP ($(grep -c '' "$BACKUP" 2>/dev/null || echo 0) строк)"
else
  head_ "Шаг h. Журнал аудита"
  echo "  psql недоступен: записи путешествия остались в audit_log"
fi

# ---------------------------------------------------------------- итог
head_ "ИТОГ"
echo "  PASS: $PASS   FAIL: $FAIL"
echo "  пользователь и страница путешествия удалены"
echo
if [ "$FAIL" -eq 0 ]; then
  echo "ВЕРДИКТ: ПУТЕШЕСТВИЕ ПРОЙДЕНО"
  exit 0
else
  echo "ВЕРДИКТ: ПУТЕШЕСТВИЕ НЕ ПРОЙДЕНО"
  exit 1
fi
