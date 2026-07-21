# Frontend Inbox

Этот файл - очередь сообщений от backend к frontend.

Правила:

- Backend добавляет каждое новое сообщение отдельной строкой в раздел `Pending`.
- Сообщение должно кратко объяснять, что изменилось, какие endpoints/поля затронуты и что нужно сделать на frontend.
- Frontend после обработки удаляет только конкретную обработанную строку.
- Не редактируйте `docs/openapi.json` руками: он генерируется автоматически.

Формат строки:

`- YYYY-MM-DD - <что изменилось> -> <что сделать frontend>`

## Pending

- 2026-07-21 - В `CreateWithdrawalRequest` добавлено обязательное поле `payerBankType` (`TBANK_AUTO`, `SBERBANK`, `ANY_BANK`), а `WithdrawalResponse` теперь возвращает `payerBankType`, `payerBankTypeTitle`, `autoReleaseEnabled` -> добавить выбор банка отправителя в форму создания и отображение этого условия в списках/деталях заявки.
