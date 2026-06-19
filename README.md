# FlowPay Backend

Spring Boot API для управления выплатами через Bybit P2P.

## Авторизация

Все маршруты, кроме `GET /api/auth/csrf` и `POST /api/auth/login`, требуют
авторизации. После успешного входа backend создаёт обычную сессию и постоянный
remember-me токен в PostgreSQL. Браузер восстанавливает вход после перезапуска
backend и остаётся авторизованным до явного `POST /api/auth/logout`.

Обязательные переменные:

```dotenv
AUTH_USERNAME=operator
AUTH_PASSWORD_HASH='{bcrypt}$2a$...'
AUTH_REMEMBER_ME_KEY=случайная-строка-длиной-не-менее-32-символов
```

Хеш должен быть BCrypt с префиксом `{bcrypt}`. Его можно создать через Spring
Boot CLI. В `.env` хеш нужно заключить в одинарные кавычки, чтобы Docker Compose
не интерпретировал символы `$`:

```bash
spring encodepassword 'ваш пароль'
```

Секрет для remember-me можно создать командой:

```bash
openssl rand -base64 48
```

Не меняйте `AUTH_REMEMBER_ME_KEY` без необходимости: при смене ключа сохранённые
браузеры должны будут войти заново. В production приложение должно открываться
только через HTTPS.

Дополнительные настройки:

```dotenv
AUTH_REMEMBER_ME_VALIDITY=30d
AUTH_MAX_FAILED_ATTEMPTS=10
AUTH_FAILURE_WINDOW=5m
```

В production remember-me cookie автоматически получает флаг `Secure` и
передаётся браузером только через HTTPS.

## Production-запуск

Скопируйте production-шаблон и заполните все секреты:

```bash
cp .env.prod.example .env
chmod 600 .env
```

Проверьте итоговую конфигурацию и запустите сервисы:

```bash
docker compose -f compose.prod.yml config --quiet
docker compose -f compose.prod.yml up -d --build
docker compose -f compose.prod.yml ps
docker compose -f compose.prod.yml logs --tail=100 backend
```

В production:

- backend доступен на VPS только через `127.0.0.1:8080`;
- PostgreSQL не публикует порт на хост;
- профиль `prod` включает graceful shutdown, secure cookies и отключает Swagger;
- внутренний Actuator healthcheck работает на `127.0.0.1:8081` внутри контейнера;
- контейнер backend запускается от непривилегированного пользователя;
- лимиты памяти рассчитаны на VPS с 1 ГБ RAM и настроенным swap.

Проверить backend с самого VPS можно командой:

```bash
curl --fail http://127.0.0.1:8080/api/auth/csrf
```

Для остановки:

```bash
docker compose -f compose.prod.yml down
```

Том `postgres-data` команда `down` не удаляет. Не используйте `down -v`, если
не хотите удалить базу данных.

## Проверки

```bash
./mvnw test
```
