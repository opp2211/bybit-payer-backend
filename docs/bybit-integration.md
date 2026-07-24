# Bybit P2P Integration

The application uses the real HTTP gateway with HMAC-SHA256 request signing.
Bybit credentials are workspace-scoped: every workspace stores its own API key,
API secret, and P2P ad id. Secrets are encrypted in the database with
`APP_ENCRYPTION_KEY`.

Generate `APP_ENCRYPTION_KEY` as a base64 encoded 32-byte value and keep it
stable between deployments. If the key changes, previously saved workspace
secrets cannot be decrypted.

Required env for encrypted workspace secrets:

```env
APP_ENCRYPTION_KEY=
```

Legacy bootstrap env for the initial `ExPrime` workspace:

```env
BYBIT_API_KEY=
BYBIT_API_SECRET=
BYBIT_BASE_URL=https://api.bybit.com
BYBIT_P2P_AD_ID=
BYBIT_RECV_WINDOW_MS=10000
BYBIT_ORDER_SOURCE_SIDE=SELL
BYBIT_BALANCE_ACCOUNT_TYPE=FUND
BYBIT_BALANCE_COIN=USDT
```

`BYBIT_API_KEY`, `BYBIT_API_SECRET`, and `BYBIT_P2P_AD_ID` are no longer the
single global account used by all users. They are read only during bootstrap to
create/migrate the initial `ExPrime` workspace. New workspaces are created via
the workspace API/UI and must pass Bybit readiness before they are saved.

`BYBIT_BASE_URL` is required and must contain the Bybit API origin, for example
`https://api.bybit.com` or `https://api-testnet.bybit.com`. The gateway trims trailing slashes.
If the property is blank, readiness returns `CONFIG_MISSING` and signed requests fail before an HTTP call.

For production deployment, copy `.env.prod.example` to `.env` and fill the
Bybit values before running `compose.prod.yml`. The production Compose file
requires the API key, secret, base URL, and managed ad ID and passes the rest of
the optional Bybit settings through the environment file.

## Local profile safety

Run local development with the `local` Spring profile when the application must
not touch the real Bybit account:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The `local` profile uses `FakeBybitGateway` instead of `HttpBybitGateway`.
The fake gateway returns a stable reference rate and available USDT balance,
and keeps managed ads, P2P orders, and order chat in memory. Managed ad updates
populate the local P2P simulator, `fetchActiveOrders`, `fetchOrder`, and
`fetchChatMessages` return the simulated state, operator messages are appended
to the simulated order chat, and `releaseOrder` marks the simulated order as
finished.

The local-only simulator API is available at `/api/local/bybit-simulator/**`.
It is intended for manual testing and lets a fake counterparty create an order
from a published managed ad, write text messages, mark the order as paid, or
cancel it. The normal order watcher still performs matching, foreign-order
tracking, paid-state handling, and withdrawal completion through the
`BybitGateway` interface.

The same profile also disables receipt mail polling through
`receipt.mail.enabled=false`, so a local instance does not read or mark messages
in the production mailbox.

## Tests and CI safety

Regular unit and integration tests must not call the real Bybit API. Gateway
tests use local fake HTTP servers or mocked `BybitGateway` beans, and
`src/test/resources/application.yml` keeps the Bybit base URL blank by default.

Live Bybit checks, if they are ever needed, should be named `*ManualTests` or
`*LiveTests`. Maven Surefire excludes these patterns from the normal
`./mvnw test` run, so GitHub Actions can deploy without touching real Bybit.

To inspect the raw Bybit chat response for a specific order, set real Bybit
credentials and run the manual test explicitly:

```powershell
$env:BYBIT_API_KEY = "<key>"
$env:BYBIT_API_SECRET = "<secret>"
$env:BYBIT_BASE_URL = "https://api.bybit.com"
./mvnw -Dtest=BybitOrderChatRawManualTests#printsRawOrderChatMessages test
```

The default order id is `2074865336971419648`. It can be overridden with
`-Dbybit.chat.order-id=<orderId>`. Pagination uses `-Dbybit.chat.page-size=30`
and `-Dbybit.chat.max-pages=20` by default.

## Used Endpoints

- `POST /v5/p2p/item/online` — get public ads and take the configured 1-based ad index for rate.
  The request includes the undocumented-but-tested `payment` and `amount` fields from the working `PrimeWorker` client.
- `GET /v5/asset/transfer/query-account-coins-balance` — read transferable USDT balance.
- `POST /v5/p2p/item/info` — read managed ad details before update.
- `POST /v5/p2p/item/update` — update/relist managed ad.
- `POST /v5/p2p/item/cancel` — unpublish/remove managed ad when there are no `IN_WORK` withdrawals.
- `POST /v5/p2p/order/pending/simplifyList` — poll active P2P orders.
- `POST /v5/p2p/order/info` — read the current or terminal status of a bound order.
- `POST /v5/p2p/user/personal/info` — read the workspace Bybit `userId`, `accountId`,
  and `nickName` used for chat author classification.
- `POST /v5/p2p/order/message/send` — send requisites and operator messages to order chat.
- `POST /v5/p2p/order/message/listpage` — read the full order chat history shown in withdrawal details.
  The gateway reads pages of up to 30 messages until a page returns fewer messages or
  `BYBIT_CHAT_MESSAGE_MAX_PAGES` is reached.
- `POST /v5/p2p/order/finish` — release assets after verified receipt.

Managed ad text is built from the full payment group of the earliest queue-managed
withdrawal. The group includes payer bank type, withdrawal method, the sender first-party
requirement flag, third-party transfer flag, and for card-number withdrawals the
`recipientCardTbank` flag. Only withdrawals with that same group can be published together;
amounts inside the active group are still merged as `2420 / 5000`. `TBANK_AUTO` can publish
either `SBP` or `CARD_NUMBER` groups and uses automatic mail receipt verification.
`SBERBANK` publishes the `ACCOUNT_NUMBER` group, while `ANY_BANK` publishes the `SBP` group.

When `requireSenderFirstParty` is enabled, the managed ad description starts with
`Работаю только с 1 лицами (Имя Ф. отправителя должны совпадать с верифицированным именем на Bybit)`.
The flag is stored on the withdrawal for future chat/agent logic; currently it only changes
the managed ad text and preview.

Receipt verification must match the parsed status as a complete normalized value before calling
`/v5/p2p/order/finish`. Negative statuses such as `Неуспешно` or `Не успешно` must not be treated
as `Успешно` by substring matching.

## Notes

The gateway reads `/v5/p2p/item/info` before `/v5/p2p/item/update` and:

- takes `paymentIds` from `paymentTerms[].id` (not from `payments`, which contains payment method type IDs);
- preserves `paymentPeriod` and `priceType`;
- sends an empty `premium` for a fixed-price ad;
- converts the supported `tradingPreferenceSet` fields to strings as required by the update endpoint and does not forward unknown response-only fields;
- sends `MODIFY` for an online ad and `ACTIVE` when relisting an offline ad.

For `/v5/p2p/item/online`, the side mapping follows the working `PrimeWorker` implementation:

- `BUY` -> `"1"`
- `SELL` -> `"0"`

For order polling, the side mapping follows Bybit order docs:

- `BUY` -> `0`
- `SELL` -> `1`

Signed requests use the local application clock for `X-BAPI-TIMESTAMP`. The gateway does not call
`/v5/market/time`; `BYBIT_RECV_WINDOW_MS` is kept wider to tolerate small local clock drift.

The managed ad starts from `BYBIT_RATE_SOURCE_AD_INDEX` (15 by default). Every
`BYBIT_AD_RATE_REFRESH_INTERVAL_MINUTES` minutes it refreshes the same position once, then moves
toward `BYBIT_RATE_SOURCE_MIN_AD_INDEX` (7 by default) and never goes below it. A queue rebuild
resets the sequence. Binding a new Bybit order rebuilds the managed ad, so a rate that had already
moved to a lower position, for example 12, returns to position 15.

The effective seventh-position RUB/USDT rate includes the P2P fee in the USDT total:
`effective rate = market rate / (1 + P2P_FEE_RATE)`. For example, `75.50 / 1.00275 = 75.29294440`.

Bound orders are checked through `/v5/p2p/order/info` after they disappear from the pending list:

- status `40`, `70`, or `80` detaches the order and returns the withdrawal to publication;
- status `50` completes the withdrawal because the assets were released outside the application.

When an order is marked paid, only `TBANK_AUTO` withdrawals with `SBP` or `CARD_NUMBER`
start mail receipt verification and send the receipt email to chat. `SBERBANK` and
`ANY_BANK` move to `PAYMENT_VERIFICATION`, are marked as requiring operator attention,
skip mailbox polling, and must be released manually.

If the AI chat agent is enabled, binding a Bybit order starts an AI session instead of
immediately sending fixed requisite messages. The agent can read the withdrawal, order,
chat, and receipt-check state and can only send chat messages or mark the withdrawal as
requiring operator attention. It never calls release, cancel, or ad-management actions.
AI sessions store the bound `bybit_order_id`; if a withdrawal returns to publication after
order cancellation and later receives another Bybit order, the old session state is reset
and the confirmation flow starts again for the new order.

Before requisites are sent, the agent confirms the withdrawal conditions with the counterparty:

- sender first-party confirmation when `requireSenderFirstParty` is enabled;
- payer bank for every withdrawal;
- official T-Bank receipt to the workspace `receiptEmail` for `TBANK_AUTO`;
- optional official T-Bank receipt for `ANY_BANK` counterparties who say they pay from T-Bank;
- third-party transfer consent when the withdrawal receives payment to a third party;
- final warning with the exact amount and requisite-safety conditions.

When a mandatory condition is rejected, the agent does not send requisites and asks the
counterparty to cancel the order, while marking the withdrawal as requiring operator attention.
If the agent cannot classify the conversation, OpenAI is unavailable, a receipt is invalid, or
the counterparty repeatedly asks to release without a valid receipt, it hands off to the operator.
When the UI disables AI mode, the agent switches to dry-run behavior: it keeps preparing the next
chat message as a suggestion, while the operator remains responsible for sending it.

For `TBANK_AUTO + SBP`, receipt verification checks `Успешно`, the transfer amount
from the `Сумма` field, recipient phone, recipient name, and recipient bank rules.
If a receipt contains both `Итого` and `Сумма`, `Сумма` is authoritative; `Итого`
may include the sender commission and is used only as a fallback when no transfer
amount field is present.

For `TBANK_AUTO + CARD_NUMBER`, receipt verification checks `Успешно`, the transfer
amount from `Сумма`, and `Карта получателя`. T-Bank card withdrawals match the last
four card digits and the parsed recipient name, because T-Bank receipts show names
as `Имя Ф.`. Non-T-Bank card withdrawals match the masked card format
`123456******1234`.

Chat history is not persisted locally. Outgoing messages are sent directly to
`/v5/p2p/order/message/send`, and withdrawal details read chat history from
`/v5/p2p/order/message/listpage` through `BybitChatService`. The service keeps a
short in-memory cache per workspace/order for `CHAT_READ_CACHE_TTL_SECONDS` (5 seconds
by default), removes entries idle longer than `CHAT_READ_CACHE_MAX_IDLE_SECONDS`, and
caps the cache with `CHAT_READ_CACHE_MAX_ENTRIES`. If Bybit chat history is unavailable
and no fresh cache entry can be used, the API returns an error.

The UI chat response is formatted by the backend. `SYS_ORDER_CARD` (`msgType=11`) messages
are hidden. `msgType=0`, `msgType=103`, `roleType=sys`, and `roleType=alarm` are shown as
system messages; `msgType=5` or `msgType=6` are shown as support. Text content uses
`content.type=TEXT`; attachments use `IMAGE`, `PDF`, or `VIDEO`, and relative Bybit file
paths are resolved against `BYBIT_CHAT_FILE_BASE_URL` (`https://api2.bybit.com` by default).
Workspace Bybit `userId`/`accountId` classify own messages, and `bybit_bot_chat_messages`
stores `msgUuid` values sent by automation so the frontend can distinguish bot messages
from manual operator messages.

Automatic requisite messages depend on withdrawal method:

- `SBP`: greeting, recipient phone, `bank, recipient name`, and receipt email for auto-release groups.
- `CARD_NUMBER`: greeting, card number, optional recipient name, and receipt email for auto-release groups.
- `ACCOUNT_NUMBER`: greeting, account number, and recipient name.

Foreign orders are observation-only. They are shown while present in the active Bybit order list and removed locally after they disappear from that list. The application does not submit cancellation requests for them.
