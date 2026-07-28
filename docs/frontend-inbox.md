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

- 2026-07-28 - В `WorkspaceResponse` добавлено поле `aiChatAgentEnabled`; настройка меняется через `PUT /api/workspaces/{workspacePublicId}/ai-chat-agent` с телом `{ "enabled": boolean }`; режимы ИИ и suggestion API удалены, для заявки оставлен одноразовый `POST /api/workspaces/{workspacePublicId}/withdrawals/{withdrawalPublicId}/chat-agent/disable`, а `AiChatAgentResponse` теперь содержит boolean `enabled` без `mode` и suggested-полей -> добавить синхронные переключатели воркспейса на экраны заявок и управления воркспейсом, заменить управление режимами в заявке на необратимое выключение.
