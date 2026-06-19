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
AUTH_PASSWORD_HASH={bcrypt}$2a$...
AUTH_REMEMBER_ME_KEY=случайная-строка-длиной-не-менее-32-символов
```

Хеш должен быть BCrypt с префиксом `{bcrypt}`. Его можно создать через Spring
Boot CLI:

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
AUTH_REMEMBER_ME_VALIDITY=24855d
AUTH_MAX_FAILED_ATTEMPTS=10
AUTH_FAILURE_WINDOW=5m
```

`AUTH_REMEMBER_ME_VALIDITY` задаёт серверный срок токена примерно в 68 лет.
Браузер может применять собственный предел срока cookie, но токен обновляется
при автоматическом восстановлении входа.

## Проверки

```bash
./mvnw test
```
