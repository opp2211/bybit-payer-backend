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
WITHDRAWAL_CANCELLATION_GRACE_PERIOD_SECONDS=5
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
`BybitGateway` interface. Fake order ids are UUID-based so cancelled bindings
left in the local database do not block new simulator orders after an
application restart or simulator reset.

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

## Rate-limit recovery and ad safety

JSON `retCode=10006` has its own retry policy for every signed Bybit request. The gateway
tries at most 10 requests in total with a 200-250 ms pause between attempts by default.
`BYBIT_RATE_LIMIT_RETRY_MAX_ATTEMPTS`, `BYBIT_RATE_LIMIT_RETRY_DELAY_MS`, and
`BYBIT_RATE_LIMIT_RETRY_JITTER_MS` configure this policy. A successful response stops
the loop immediately; the tenth `10006` is propagated. Existing HTTP 429/5xx and I/O
retry settings remain separate. The total elapsed time also includes Bybit response
latency and the gateway's request throttle.

Managed ad state is updated only after the Bybit mutation succeeds. If no withdrawal
remains `IN_WORK`, the ad recovery task checks the remote ad status and keeps trying to
unpublish an online ad. A failed attempt sets persistent `unpublish_pending` and
`last_error`; retries continue after restarts with delays growing from 10 to 60 seconds.
The task runs every `BYBIT_AD_UNPUBLISH_RETRY_INTERVAL_SECONDS` (10 by default),
independently of reference-rate and balance requests. It audits the remote status
every 30 seconds even when the local state says the ad is offline, to repair
local/remote mismatches including a failed database transaction after a Bybit call.
After a successful cancel it reads the remote status again before clearing the pending
state. `GET /api/workspaces/{workspacePublicId}/system/status` exposes the pending
flag, its start time, and last unpublish error for an operator warning. Order polling
continues while unpublication is pending. A persistent Bybit outage still requires an
operator to inspect and, if necessary, remove the ad manually.

`POST /v5/p2p/item/info` is also used by ad recovery to check whether the managed ad
is online (status `10`).

## Used Endpoints

- `POST /v5/p2p/item/online` — get public ads and take the configured 1-based ad index for rate.
  The request includes the undocumented-but-tested `payment` and `amount` fields from the working `PrimeWorker` client.
- `GET /v5/asset/transfer/query-account-coins-balance` — read transferable USDT balance.
- `POST /v5/p2p/item/info` — read managed ad details before update.
- `POST /v5/p2p/item/update` — update/relist managed ad.
- `POST /v5/p2p/item/cancel` — unpublish/remove managed ad when there are no `IN_WORK` withdrawals.
- `POST /v5/p2p/order/pending/simplifyList` — poll active P2P orders.
- `POST /v5/p2p/order/info` — read the current or terminal status of a bound order, look up
  its counterparty name if missing locally, and obtain `targetUserMaskId` for chat sessions.
- `POST /v5/p2p/user/personal/info` — read the workspace Bybit `userId`, `accountId`,
  and `nickName` used for chat author classification.
- `POST /v5/p2p/chat/session/getSessionId` — obtain the encrypted chat `sessionId`; the gateway caches
  it per API key/order for the process lifetime.
- `POST /v5/p2p/chat/message/send_v1` — send requisites, completion thanks, and operator messages to order chat.
- `POST /v5/p2p/chat/message/listpage_v1` — read the full order chat history shown in withdrawal details.
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
The ad intro comes from `PayerBankType`, and its payment-method tail comes from
`WithdrawalPaymentRules`. Bank names in generated ad text use Latin initials:
`T-банк` and `Sбербанк`, including their inflected forms and the `SБЕР-SБЕР` tail.
Bank titles, incoming values, receipt validation, and UI labels retain their
existing spelling.

When `requireSenderFirstParty` is enabled, the managed ad description starts with
`Работаю только с 1 лицами (Имя Ф. отправителя должны совпадать с верифицированным именем на Bybit)`.
The flag is stored on the withdrawal and changes the managed ad text and preview.

Receipt verification must match the parsed status as a complete normalized value before calling
`/v5/p2p/order/finish`. Negative statuses such as `Неуспешно` or `Не успешно` must not be treated
as `Успешно` by substring matching.

For withdrawals with `requireSenderFirstParty` enabled, automatic release also checks the
receipt's `Отправитель` against the verified name from the attached Bybit order. Names are
compared case-insensitively using the first and third words for three-word names or both words
for two-word names. Russian Cyrillic and Latin names can also match after exact transliteration
using the Russian letter mappings in [ICAO Doc 9303, Part 3](https://www.icao.int/publications/Documents/9303_p3_cons_en.pdf).
Missing, unsupported, and mismatched names require operator review and show an error in
withdrawal details. This includes differences such as a missing letter or an alternative spelling.
The same check applies when retrying release with a saved receipt.

The withdrawal sound feed reads the latest 100 qualifying events from the last five minutes
for the selected workspace. It includes bound and cancelled orders, attention flags, appeals
and objections, and withdrawal errors. The first response establishes a baseline for the
browser, and chat activity is excluded. The existing completion sound uses unseen completed
withdrawals.

`GET /api/workspaces/{workspacePublicId}/withdrawals/updates` streams `ready`, `withdrawal`,
and `heartbeat` as server-sent events. Withdrawal events are published after the database
commit. The stream checks the authenticated session and workspace membership. The frontend
refreshes the affected withdrawal queries and reads recent sound events on demand, including
after reconnecting.

On a withdrawal detail page, the same stream accepts `?chat={withdrawalPublicId}`.
A supported chat subscription receives `ready` with data `chat`, then `chat` events whose
data is the withdrawal public ID. The frontend reloads the details snapshot for that ID.
Chat events carry no message content and do not trigger sound. The backend refreshes each
subscribed order once across all viewers, using the existing cache TTL and failure backoff.
Successful sends also invalidate the open chat. The details response keeps cached messages
and sets `chatUnavailable` when Bybit chat history could not be refreshed. The frontend
reloads the snapshot after reconnecting and polls details while chat events are unavailable.

## Notes

Managed advertisement rebuilds propagate failures without logging them inside
`rebuildPublication(workspace)`. An HTTP request logs an unhandled failure with
its request ID, method, path, and stack trace. Background callers that continue
after a failed workspace log the failure once at their catch boundary, including
the workspace public ID and stack trace. Expected `BusinessException` failures
in the all-workspace rebuild are logged as warnings without a stack trace.

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

Order polling commits a new order binding and its withdrawal state before dispatching chat
messages and rebuilding the managed ad. Chat dispatch and ad synchronization therefore use
separate transactions. An ad rebuild failure, including insufficient transferable USDT balance,
cannot roll back a visible order binding or cause the fixed requisite messages to be sent again
on the next poll.

The effective seventh-position RUB/USDT rate includes the P2P fee in the USDT total:
`effective rate = market rate / (1 + P2P_FEE_RATE)`. For example, `75.50 / 1.00275 = 75.29294440`.

Withdrawal details expose `bybitCounterpartyVerifiedName` for the attached order. Order
polling stores Bybit's `buyerRealName` for sell orders or `sellerRealName` for buy orders,
according to the order's `side`. If the name is missing locally, the details endpoint
looks it up via `/v5/p2p/order/info` with a bounded cache using one-minute windows, including
unavailable responses. The name is cleared when a cancelled order is detached.

Bound orders are checked through `/v5/p2p/order/info` after they disappear from the pending list:

- status `40`, `70`, or `80` detaches the order and returns the withdrawal to publication;
- status `50` completes the withdrawal because the assets were released outside the application.

The active-order endpoint is read page by page (up to 30 orders per page) before
matching starts, so orders beyond the first page are not skipped.

Dispute statuses are synchronized even when the binding was already marked as released:

- status `30` (appealing) and `100` (objectioning) move the withdrawal to `APPEAL`;
- status `110` (waiting for the user to raise an objection) moves it to the separate
  `OBJECTION_REQUIRED` status;
- a disputed order ending in `40`, `70`, or `80` moves the withdrawal to `CANCELLED`;
- a disputed order ending in `50` moves the withdrawal back to `COMPLETED`.

`APPEAL` and `OBJECTION_REQUIRED` are active withdrawal statuses and require operator
attention, so a previously completed withdrawal becomes visible in the active list again.

Cancelling an `IN_WORK` withdrawal uses a safety window to cover the normal order-polling
race. The withdrawal first moves to `CANCELLATION_PENDING`, the managed ad is rebuilt
without it (or unpublished if no other withdrawal remains), and the application waits
`WITHDRAWAL_CANCELLATION_GRACE_PERIOD_SECONDS` (5 seconds by default). It then performs
an immediate full active-order poll. If a late order is found, the normal binding flow
attaches it to the withdrawal and cancellation is rejected; otherwise the withdrawal is
cancelled. An order already bound to another withdrawal never blocks this cancellation
merely because its RUB amount is the same.

When an order is marked paid, only `TBANK_AUTO` withdrawals with `SBP` or `CARD_NUMBER`
start mail receipt verification. `SBERBANK` and
`ANY_BANK` move to `PAYMENT_VERIFICATION`, are marked as requiring operator attention,
skip mailbox polling, and must be released manually.

When a new order is bound, the backend sends the greeting and fixed requisite messages.
For automatic receipt verification, it also sends the workspace receipt email. If the
withdrawal has `requireSenderFirstParty` enabled, the backend sends a separate fixed warning
as the last automatic message, after any receipt email. The warning asks the buyer to pay
from an account in their verified Bybit name and explains that unapproved third-party
payments will be returned to the sender. Operators can send additional messages from the
withdrawal details.

When a bound order is successfully released, the binding schedules the message `Спасибо`.
The sender checks the buyer's `transferDate` in `/v5/p2p/order/info` against the recorded
completion time and sends only for elapsed times from zero through three minutes. When
release is detected by polling Bybit, the recorded completion time is the time of detection.
Unknown payment times are skipped. The worker claims each binding after the completion
transaction commits and attempts the chat send once, since the Bybit chat endpoint has no
idempotency key. A chat failure leaves the completed withdrawal intact.

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

Outgoing messages are sent directly to
`/v5/p2p/chat/message/send_v1`, and withdrawal details read chat history from
`/v5/p2p/chat/message/listpage_v1` through `BybitChatService`. A chat `sessionId` is resolved once
from the order's `targetUserMaskId` and reused. The service keeps an in-memory cache per
workspace/order for `CHAT_READ_CACHE_TTL_SECONDS` (15 seconds by default), removes entries idle
longer than `CHAT_READ_CACHE_MAX_IDLE_SECONDS`, and caps the cache with
`CHAT_READ_CACHE_MAX_ENTRIES`. A failed chat read starts a
`CHAT_READ_FAILURE_BACKOFF_SECONDS` cooldown (60 seconds by default). During the cooldown no
Bybit chat request is made and withdrawal details use stale cached chat or an empty list, so a
chat outage does not make the whole details endpoint unavailable.

The UI chat response is formatted by the backend. `SYS_ORDER_CARD` (`msgType=11`) messages
are hidden. `msgType=0`, `msgType=103`, `roleType=sys`, and `roleType=alarm` are shown as
system messages; `msgType=5` or `msgType=6` are shown as support. Text content uses
`content.type=TEXT`; attachments use `IMAGE`, `PDF`, or `VIDEO`, and relative Bybit file
paths are resolved against `BYBIT_CHAT_FILE_BASE_URL` (`https://api2.bybit.com` by default).
Workspace Bybit `userId`/`accountId` classify own messages from legacy-shaped data; the new chat
API only returns the sender nickname, which is used as a fallback. `bybit_bot_chat_messages`
stores locally generated `msgUuid` values so freshly appended automation messages can be
distinguished from manual operator messages while they remain in the local cache.

Backend-appended copyable requisite messages depend on withdrawal method:

- `SBP`: recipient phone, `bank, recipient name`, and receipt email for auto-release groups.
- `CARD_NUMBER`: card number, `bank, recipient name`, and receipt email for auto-release groups.
- `ACCOUNT_NUMBER`: account number and `bank, recipient name`.

Foreign orders are observation-only. They are shown while present in the active Bybit order list and removed locally after they disappear from that list. The application does not submit cancellation requests for them.
