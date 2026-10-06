# Система авторизации и управления доступом

Веб-приложение с JWT-авторизацией, ролями и гибким доступом
к трём внутренним тестовым страницам.

## Стек

| Слой      | Технология                                          |
|-----------|-----------------------------------------------------|
| Бэкенд    | Spring Boot 3.5, Spring Security, JJWT, Java 21     |
| Фронтенд  | Vue 3, Pinia, Vue Router, Axios, Vite               |
| База      | PostgreSQL (Flyway-миграции)                        |
| Сессии    | Redis (refresh-токены)                              |
| Деплой    | nginx + systemd, Ubuntu 24.04                       |

## Возможности

- Вход по email и паролю (Argon2id-хеш пароля).
- Двухтокенная схема: access 15 минут (в памяти браузера),
  refresh 7 дней (HttpOnly Secure SameSite=Strict cookie, в Redis только SHA-256 хеш).
- Ротация refresh-токена; повторное использование старого токена
  отзывает всю семью сессий устройства.
- Роли: ADMIN, USER.
- Страницы: TEST_PAGE_1, TEST_PAGE_2, TEST_PAGE_3.
  Доступ выдаётся администратором в любой комбинации.
- Админка: создание, удаление, роль, статус, набор страниц.
- Защита от перебора паролей: 5 неудач за 5 минут на связку IP+email.
- Проверка прав всегда на бэкенде, фронтенд лишь скрывает ссылки.

## API

| Метод  | Путь                    | Доступ | Назначение                    |
|--------|-------------------------|--------|-------------------------------|
| POST   | /api/auth/login         | все    | вход                          |
| POST   | /api/auth/refresh       | все    | обмен refresh на новый access |
| POST   | /api/auth/logout        | вход   | выход, отзыв токена           |
| GET    | /api/auth/me            | вход   | текущий пользователь          |
| GET    | /api/admin/users        | ADMIN  | список пользователей          |
| POST   | /api/admin/users        | ADMIN  | создать пользователя          |
| PATCH  | /api/admin/users/{id}   | ADMIN  | роль / статус                 |
| PUT    | /api/admin/users/{id}/pages | ADMIN | назначить страницы        |
| DELETE | /api/admin/users/{id}   | ADMIN  | удалить пользователя          |
| GET    | /api/pages/{code}       | право  | содержимое тестовой страницы  |

## Переменные окружения (бэкенд)

DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD,
REDIS_HOST, REDIS_PORT, REDIS_PASSWORD,
JWT_PRIVATE_KEY_PATH, JWT_PUBLIC_KEY_PATH,
ADMIN_EMAIL, ADMIN_INITIAL_PASSWORD (только для первого запуска).

В продакшене все секреты лежат в /etc/auth-backend/env (права 640),
вне репозитория и вне веб-папки.

## Документация по запуску

- Руководство пользователя и администратора: docs/USER_GUIDE.md
- Локальная разработка и продакшен-деплой: docs/RUNBOOK.md