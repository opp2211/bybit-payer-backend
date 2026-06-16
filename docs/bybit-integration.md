# Bybit P2P Integration

The application uses the real HTTP gateway with HMAC-SHA256 request signing.

Required env for real gateway:

```env
BYBIT_API_KEY=
BYBIT_API_SECRET=
BYBIT_ENV=testnet
BYBIT_BASE_URL=
BYBIT_P2P_AD_ID=
BYBIT_RECV_WINDOW_MS=5000
BYBIT_ORDER_SOURCE_SIDE=SELL
BYBIT_BALANCE_ACCOUNT_TYPE=FUND
BYBIT_BALANCE_COIN=USDT
```

`BYBIT_BASE_URL` is optional. The application config defaults it to `https://api.bybit.com`.
If the property is blank, the gateway falls back to `BYBIT_ENV`:

- `BYBIT_ENV=testnet` -> `https://api-testnet.bybit.com`
- `BYBIT_ENV=mainnet` -> `https://api.bybit.com`

## Used Endpoints

- `POST /v5/p2p/item/online` — get public ads and take the configured 1-based ad index for rate.
  The request includes the undocumented-but-tested `payment` and `amount` fields from the working `PrimeWorker` client.
- `GET /v5/asset/transfer/query-account-coins-balance` — read transferable USDT balance.
- `POST /v5/p2p/item/info` — read managed ad details before update.
- `POST /v5/p2p/item/update` — update/relist managed ad.
- `POST /v5/p2p/item/cancel` — unpublish/remove managed ad when there are no `IN_WORK` withdrawals.
- `POST /v5/p2p/order/pending/simplifyList` — poll active P2P orders.
- `POST /v5/p2p/order/info` — read the current or terminal status of a bound order.
- `POST /v5/p2p/order/message/send` — send requisites to order chat.
- `POST /v5/p2p/order/finish` — release assets after verified receipt.
- `POST /v5/p2p/order/cancel` — experimentally cancel a P2P order by id.

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

The gateway requests `/v5/market/time` before signed requests and falls back to local time if server time is temporarily unavailable.

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

Foreign orders are observation-only. They are shown while present in the active Bybit order list and removed locally after they disappear from that list. The application does not submit cancellation requests for them.

## Manual Order Cancellation Test

`HttpBybitGatewayManualTests` is disabled by default. To try the experimental order cancellation endpoint,
hardcode `BYBIT_ORDER_ID` in the test and run:

```powershell
.\mvnw -Dtest=HttpBybitGatewayManualTests -Dbybit.manual.cancel-order-test=true test
```

The test loads Bybit settings from the normal test configuration, environment variables, and optional `.env`.
