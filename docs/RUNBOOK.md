# RUNBOOK: запуск и деплой

## А. Локальная разработка

Требования: Java 21, Maven, Node.js 18+, Docker.

    cd project
    docker compose up -d          # PostgreSQL + Redis
    bash deploy/gen-keys.sh       # RSA-ключи для разработки

    cd backend
    export DB_PASSWORD=dev_only_change_me
    export REDIS_PASSWORD=dev_only_change_me
    export ADMIN_INITIAL_PASSWORD=dev-admin-pass
    mvn spring-boot:run           # бэкенд на http://localhost:8080

    cd ../frontend
    npm install
    npm run dev                   # фронтенд на http://localhost:5173

Вход: admin@seoniks.ru / dev-admin-pass
(профиль dev отключает Secure у cookie для http://localhost)

## Б. Продакшен (Ubuntu 24.04, один раз)

0. Рекомендуемый харденинг до всего остального:
       - создать личного пользователя с sudo;
       - настроить вход по SSH-ключу;
       - отключить вход по паролю и вход под root в /etc/ssh/sshd_config;
       - systemctl restart ssh
   Делайте осторожно, не закрывайте текущую сессию до проверки новой.

1. Вынести проект из веб-папки (если он внутри www):
       mv /var/www/u3664086/data/www/praktika31.ru/project /var/www/u3664086/data/project
   Удалить из веб-папки временные скрипты:
       rm -f create-project.php fill-1.php fill-2.php fill-3.php fill-4.php

2. Установка компонентов:
       cd /var/www/u3664086/data/project
       bash deploy/install-server.sh
   Скрипт поставит Java, Maven, Node, nginx, PostgreSQL, Redis,
   создаст пользователя authapp, ключи, секреты, базу, файрвол, fail2ban.
   ВНИМАНИЕ: скрипт выведет пароль первичного администратора — сохраните его.

3. DNS: у регистратора домена создать A-запись
       app  ->  195.208.60.96
   (или другой ваш домен; тогда поправьте server_name
    в /etc/nginx/conf.d/zz-auth-app.conf)

4. Сборка и выкладка:
       bash deploy/deploy.sh

5. HTTPS (после применения DNS):
       certbot --nginx -d app.seoniks.ru

6. Первый вход: https://app.seoniks.ru/login
   Email: ADMIN_EMAIL из /etc/auth-backend/env
   Пароль: ADMIN_INITIAL_PASSWORD оттуда же.
   Сразу создайте через админку личного администратора,
   затем удалите первичного (удаление себя запрещено).

## В. Проверки после деплоя

    systemctl status auth-backend
    journalctl -u auth-backend -f
    curl -i http://127.0.0.1:8080/api/auth/me      # ожидание: 401
    curl -i https://app.seoniks.ru/api/auth/me     # ожидание: 401

## Г. Частые проблемы

| Симптом                        | Причина / действие                                    |
|--------------------------------|-------------------------------------------------------|
| 502 от nginx                   | сервис не поднят: journalctl -u auth-backend -e       |
| Ошибка Flyway при старте       | неверные DB_* в /etc/auth-backend/env                 |
| NOAUTH от Redis                | не совпадает REDIS_PASSWORD и requirepass в redis.conf|
| Цикл 401 в браузере            | сайт открыт по http, а cookie Secure: нужен HTTPS     |
| nginx -t ошибка                | конфликт server_name: проверьте zz-auth-app.conf      |
| Панель ispmanager недоступна   | проверьте порты панели в ufw: ss -tlnp | grep ispmgr   |

## Д. Обновление приложения

    cd /var/www/u3664086/data/project
    git pull            # если ведёте репозиторий
    bash deploy/deploy.sh

## Е. Бэкапы

    sudo -u postgres pg_dump authdb > /root/backup-authdb-$(date +%F).sql
Копию файла уносите вне сервера. Redis бэкапить не нужно:
refresh-токены восстанавливаются повторным входом пользователей.