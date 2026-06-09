# Bybit P2P Integration

Gateway selection:

- `BYBIT_ENABLED=false` — local no-op gateway, no network calls.
- `BYBIT_ENABLED=true` — real HTTP gateway with HMAC-SHA256 request signing.

Required env for real gateway:

```env
BYBIT_ENABLED=true
BYBIT_API_KEY=
BYBIT_API_SECRET=
BYBIT_ENV=testnet
BYBIT_BASE_URL=
BYBIT_WEB_BASE_URL=https://api2.bybit.com
BYBIT_WEB_SESSION_COOKIE=
BYBIT_WEB_GUID=
BYBIT_P2P_AD_ID=
BYBIT_RECV_WINDOW_MS=5000
BYBIT_ORDER_SOURCE_SIDE=SELL
BYBIT_CANCEL_REASON_CODE=sellerOrderCancelReason_sellerOther
BYBIT_CANCEL_SUB_REASON_CODE=
BYBIT_CANCEL_REMARK=
BYBIT_BALANCE_ACCOUNT_TYPE=FUND
BYBIT_BALANCE_COIN=USDT
```

`BYBIT_BASE_URL` is optional. Defaults:

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

- `POST https://api2.bybit.com/fiat/otc/order/seller/proposal/cancelOrder` - experimental seller-side cancel request submit. This is a web-session endpoint, not a signed public v5 endpoint.

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

The checked Bybit P2P docs and the official `bybit_p2p` SDK do not expose a public signed v5 endpoint for submitting a seller-side order cancellation request after the buyer marks a foreign order as paid.

The Bybit web bundle exposes an internal endpoint:

```http
POST https://api2.bybit.com/fiat/otc/order/seller/proposal/cancelOrder
```

Payload used by the web UI:

```json
{
  "orderId": "...",
  "subCancelReasonCode": null,
  "cancelReasonCode": "sellerOrderCancelReason_sellerOther",
  "cancelRemark": ""
}
```

The related reason list endpoint is:

```http
POST https://api2.bybit.com/fiat/otc/order/config/cancelReasonList
```

with:

```json
{
  "orderStatus": "30",
  "cancelType": "SELLER_CANCEL"
}
```

Known seller reason codes from that response:

- `sellerOrderCancelReason_sellerDonotWantTrade`
- `sellerOrderCancelReason_sellerWantChangePrice`
- `sellerOrderCancelReason_sellerAccountIssues`
- `sellerOrderCancelReason_buyerDidnotPay`
- `sellerOrderCancelReason_buyerDidnotFullPay`
- `sellerOrderCancelReason_buyerPayedViolatePolicy`
- `sellerOrderCancelReason_buyerPaymentAccount`
- `sellerOrderCancelReason_sellerOther`

`/v5/p2p/order/seller/proposal/cancelOrder` was checked and returned 404. The internal `/fiat/otc/...` endpoint returned `10007 User authentication failed` without browser cookies and ignored fake `X-BAPI-*` headers, so `requestCancel` requires `BYBIT_WEB_SESSION_COOKIE`. Without that cookie it throws an explicit `BybitApiException` instead of guessing.
