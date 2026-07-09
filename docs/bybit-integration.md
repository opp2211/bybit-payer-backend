# Bybit P2P Integration

The application uses the real HTTP gateway with HMAC-SHA256 request signing.

Required env for real gateway:

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
returns no active orders or chat messages, and treats ad updates, ad unpublishing,
chat messages, and order releases as no-op operations with log messages only.

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

## Used Endpoints

- `POST /v5/p2p/item/online` — get public ads and take the configured 1-based ad index for rate.
  The request includes the undocumented-but-tested `payment` and `amount` fields from the working `PrimeWorker` client.
- `GET /v5/asset/transfer/query-account-coins-balance` — read transferable USDT balance.
- `POST /v5/p2p/item/info` — read managed ad details before update.
- `POST /v5/p2p/item/update` — update/relist managed ad.
- `POST /v5/p2p/item/cancel` — unpublish/remove managed ad when there are no `IN_WORK` withdrawals.
- `POST /v5/p2p/order/pending/simplifyList` — poll active P2P orders.
- `POST /v5/p2p/order/info` — read the current or terminal status of a bound order.
- `POST /v5/p2p/order/message/send` — send requisites and operator messages to order chat.
- `POST /v5/p2p/order/message/listpage` — read the full order chat history shown in withdrawal details.
- `POST /v5/p2p/order/finish` — release assets after verified receipt.

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

Chat messages are not stored locally. Outgoing messages are sent directly to
`/v5/p2p/order/message/send`, and withdrawal details read chat history only from
`/v5/p2p/order/message/listpage`. If Bybit chat history is unavailable, the API
returns an error instead of falling back to cached local messages.

Foreign orders are observation-only. They are shown while present in the active Bybit order list and removed locally after they disappear from that list. The application does not submit cancellation requests for them.
