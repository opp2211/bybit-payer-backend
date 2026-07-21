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

- 2026-07-21 - В `CreateWithdrawalRequest` добавлены обязательные поля `withdrawalMethod`, `thirdPartyTransfer`, `recipientCardTbank`, а также реквизиты `recipientCardNumber`/`recipientAccountNumber`; `recipientPhone`/`recipientBank`/`recipientName` теперь обязательны только для отдельных методов. `WithdrawalResponse` возвращает `withdrawalMethod`, `withdrawalMethodTitle`, `recipientCardNumber`, `recipientAccountNumber`, `recipientCardTbank`, `thirdPartyTransfer`; `EmailReceiptCheckResponse` возвращает `parsedRecipientCard` -> обновить форму создания и отображение реквизитов по методам вывода.
