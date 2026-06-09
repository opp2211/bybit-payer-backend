# Frontend REST Contract

Base URL: `http://localhost:8080`

Формат ошибок для всех новых endpoint'ов:

```json
{
  "message": "Human readable error",
  "details": ["optional detail"]
}
```

## Enum'ы

`WithdrawalStatus`:

```ts
type WithdrawalStatus =
  | "NEW"
  | "QUEUED"
  | "IN_WORK"
  | "PAYMENT_IN_PROGRESS"
  | "PAYMENT_VERIFICATION"
  | "COMPLETED"
  | "CANCELLED"
  | "ERROR";
```

`Bank`:

```ts
type Bank = {
  code: string;
  title: string;
};
```

Список банков загружается через `GET /api/banks`. При создании заявки фронт отправляет `code`; бэкенд также принимает `title`.

## Банки

### `GET /api/banks`

Получить активные банки в порядке, заданном в БД.

Response `200 OK`: `Bank[]`.

## Polling

- `GET /api/withdrawals/active` — каждые 2 секунды.
- `GET /api/foreign-orders/active` — каждые 2 секунды.
- `GET /api/withdrawals/completed` — каждые 5 секунд.
- `GET /api/system/status` — каждые 5 секунд.

Звук завершения проигрывать для заявки со статусом `COMPLETED`, пока `completionSeen === false`. После показа вызвать `POST /api/withdrawals/{id}/mark-seen`.

## Заявки

### `POST /api/withdrawals`

Создать заявку.

Request:

```ts
type CreateWithdrawalRequest = {
  amountRub: number;          // положительное целое RUB
  recipientPhone: string;     // будет нормализован в +7XXXXXXXXXX
  recipientBank: string;      // code or title from GET /api/banks
  recipientName: string;      // trim, дальше строго сравнивается с PDF
};
```

Response `201 Created`: `Withdrawal`.

### `GET /api/withdrawals/active`

Response: `Withdrawal[]` со статусами `NEW`, `QUEUED`, `IN_WORK`, `PAYMENT_IN_PROGRESS`, `PAYMENT_VERIFICATION`, `ERROR`.

`ERROR` после этапа проверки оплаты означает, что найден и привязан чек с номером
телефона заявки, но остальные данные чека не прошли проверку. В этом случае:

- `attentionRequired === true`;
- `lastWarning` содержит список несовпавших полей;
- `GET /api/withdrawals/{id}` содержит привязанный чек с
  `verificationStatus: "FAILED"` и подробностью в `verificationError`;
- автоматический поиск новых чеков для этой заявки прекращается.

Чеки с другим номером телефона к заявке не привязываются и не создают
`EmailReceiptCheck`.

### `GET /api/withdrawals/completed`

Response: `Withdrawal[]` со статусом `COMPLETED`, новые завершения имеют `completionSeen: false`.

### `GET /api/withdrawals/{id}`

Response:

```ts
type WithdrawalDetails = {
  withdrawal: Withdrawal;
  events: WithdrawalEvent[];
  chatMessages: ChatMessageLog[];
  receiptChecks: EmailReceiptCheck[];
};
```

### `DELETE /api/withdrawals/{id}`

Отменить заявку, если статус позволяет (`NEW`, `QUEUED`, `IN_WORK`).

Response: `Withdrawal`.

### `POST /api/withdrawals/{id}/mark-seen`

Подтвердить просмотр завершенной заявки.

Response: `Withdrawal`.

### DTO

```ts
type Withdrawal = {
  id: number;
  amountRub: number;
  recipientPhone: string;
  recipientBank: string;
  recipientBankTitle: string;
  recipientName: string;
  status: WithdrawalStatus;
  statusTitle: string;
  attentionRequired: boolean;
  completionSeen: boolean;
  queueGroupKey: string | null;
  queuePosition: number | null;
  bybitOrderId: string | null;
  bybitOrderAmountRub: number | null;
  createdAt: string;
  queuedAt: string | null;
  publishedAt: string | null;
  orderFoundAt: string | null;
  requisitesSentAt: string | null;
  paidAt: string | null;
  verificationStartedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  lastError: string | null;
  lastWarning: string | null;
  canCancel: boolean;
};

type WithdrawalEvent = {
  id: number;
  eventType: string;
  message: string;
  payloadJson: string | null;
  createdAt: string;
};

type ChatMessageLog = {
  id: number;
  bybitOrderId: string;
  messageIndex: number;
  messageText: string;
  status: "PENDING" | "SENT" | "FAILED";
  sentAt: string | null;
  error: string | null;
};

type EmailReceiptCheck = {
  id: number;
  bybitOrderId: string | null;
  emailMessageId: string | null;
  emailFrom: string | null;
  emailSubject: string | null;
  emailReceivedAt: string | null;
  pdfFilename: string | null;
  parsedStatus: string | null;
  parsedAmountRub: number | null;
  parsedRecipientPhone: string | null;
  parsedRecipientBank: string | null;
  parsedRecipientName: string | null;
  parsedOperationDate: string | null;
  parsedOperationId: string | null;
  parsedReceiptNumber: string | null;
  verificationStatus: "FOUND" | "VERIFIED" | "FAILED";
  verificationError: string | null;
  createdAt: string;
};
```

## Чужие ордера

### `GET /api/foreign-orders/active`

Response: `ForeignBybitOrder[]`.

### `GET /api/foreign-orders/{id}`

Response: `ForeignBybitOrder`.

```ts
type ForeignBybitOrder = {
  id: number;
  bybitOrderId: string;
  amountRub: number;
  bybitStatus: string | null;
  reason: string;
  cancelRequested: boolean;
  cancelRequestAttempts: number;
  cancelRequestedAt: string | null;
  attentionRequired: boolean;
  createdAt: string;
  updatedAt: string;
  lastError: string | null;
};
```

## Системный статус

### `GET /api/system/status`

Возвращает локальный снимок состояния. Проверка Bybit выполняется в фоне и не зависит от частоты polling фронта.

Response:

```ts
type SystemStatus = {
  bybitApiAvailable: boolean;
  bybitMode: "LOCAL_NOOP" | "CONFIG_MISSING" | "HTTP" | string;
  gmailImapsAvailable: boolean;
  bybitAdId: string | null;
  adPublished: boolean;
  currentRate: number | null;
  currentMinRub: number | null;
  currentMaxRub: number | null;
  currentQuantityUsdt: number | null;
  currentDescription: string | null;
  availableUsdtBalance: number | null;
  lastSystemError: string | null;
  bybitLastCheckedAt: string | null;
  lastUpdatedAt: string | null;
};
```

### `POST /api/system/resync`

Запускает безопасную пересборку очереди и состояния объявления.

Response: `SystemStatus`.

## Receipt debug API

Существующие debug endpoint'ы сохранены:

- `POST /api/tinkoff-receipts/pdf/verify`
- `POST /api/tinkoff-receipts/mail/verify`

Они нужны для ручной проверки PDF/почты и не участвуют напрямую в основном UI-потоке заявок.
