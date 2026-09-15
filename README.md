# RefundOps payment recovery demo

RefundOps uses only the in-process `MockPayProvider`; it never makes an external
financial call. Configure demo passwords with `REFUNDS_DAHNESH_PASSWORD` and
`REFUNDS_SHWETA_PASSWORD`, then run `mvn spring-boot:run`.

## Synthetic fault modes

Set `REFUNDS_MOCKPAY_FAULT_PROFILE` before startup. The value is read-only while
the application is running and defaults to `SUCCESS`.

| Value | Deterministic behavior per provider idempotency key |
| --- | --- |
| `SUCCESS` | Returns one receipt. |
| `CONFIRMED_NOT_SENT_ONCE` | First call fails before acceptance; one explicit retry can succeed. |
| `ACCEPTED_RESPONSE_LOST_ONCE` | First call stores a receipt, then loses the response. |

The stable key `mockpay-refund:<refund-id>` is reused for receipt lookup and any
permitted retry. The shared demo reset clears refunds, recovery records, injected
fault history, and MockPay receipts under the same service lock; it does not
change the configured fault mode.

## Safe recovery

Provider failures remain visible in the dashboard and the Approvals view.
Recovery endpoints require the `APPROVER` role and a valid Spring Security CSRF
token.

* For `PROVIDER_OUTCOME_UNKNOWN`, use
  `POST /api/refunds/{refundId}/reconcile`. It only queries MockPay's receipt
  registry and never sends another instruction.
* For `PROVIDER_RETRY_REQUIRED`, use
  `POST /api/refunds/{refundId}/retry` with JSON `{"confirmed":true}`. Retry is
  rejected unless MockPay previously confirmed nothing was sent. High-value
  refunds must already contain an independent approval.

There are no automatic retry loops. Replaying the original refund or approval
request returns or reports the recorded recovery state instead of resubmitting.

## Limitations

This is a local demonstration, not a production queue, payment integration, or
settlement system. Orders, submissions, receipts, and recovery state are
in-memory and are lost on process restart. MockPay receipts indicate accepted
synthetic instructions, not bank settlement.
