# Point of Sale (JavaFX)

Desktop cashier and inventory system: checkout, stock control, refunds, shifts, reports and an audit trail.
Single store, single terminal, data stored in an embedded H2 database.

## Requirements

- JDK 26 (set in `pom.xml` as `maven.compiler.release`; change it if you use another JDK)
- Apache Maven 3.9+

## Run

```
mvn javafx:run
```

The database is created on first run at `%USERPROFILE%\.cashier-app\data\pos`. Delete that folder to start fresh.

Tests:

```
mvn test
```

## First login

A fresh database has one account, `admin` with password `admin`. You must choose a new password at first login.
Then, as admin, go to **Users** and create the staff accounts (cashiers, managers). New accounts get a temporary
password and must change it at their first login.

## Roles

| Screen    | Cashier | Manager | Admin |
|-----------|:-------:|:-------:|:-----:|
| Checkout  | yes     | yes     | yes   |
| Shift     | yes     | yes     | yes   |
| Products  |         | yes     | yes   |
| Stock     |         | yes     | yes   |
| Refunds   |         | yes     | yes   |
| Reports   |         | yes     | yes   |
| Users     |         |         | yes   |

Permission checks run in the service layer, not only in the UI.

## Daily use

1. **Shift:** open a shift with the cash float. Every sale belongs to the open shift.
2. **Checkout:** scan a barcode or type a SKU or name and press Enter. Double-click a search result to add it.
   Use *Quantity...* and *Discount %...* on a cart line. Press **F9** or *Pay* to take payment.
   Card and cash can be split: enter a card amount and card number; cash covers the rest.
   Simulated cards with a number ending in `0002` are declined.
3. **Discounts** above the store threshold (10% by default) need a manager's username and password.
4. **Shift close:** count the drawer. The result shows the variance against expected cash.

## Money and stock rules

- Money is stored as whole cents. Tax rates are stored in basis points (`800` = 8%).
- Tax is rounded per line, so receipts add up exactly.
- Sales, payments, refunds and stock movements are append-only. Corrections are new rows.
- Every stock change is a movement row. The quantity on the product is a cached total of those rows.
- A refund never returns more than was sold. The last unit of a line is refunded for the exact remaining amount,
  so partial refunds never lose a cent.

## Store settings

Settings live in the `settings` table and are not yet editable in the app:

| Key | Default | Meaning |
|-----|---------|---------|
| `store_name` | My Store | Shown on the login screen and receipts |
| `allow_negative_stock` | false | Allow selling more than is on hand |
| `discount_approval_threshold_bp` | 1000 | Line discounts above this need manager approval |

## Project layout

```
src/main/java/com/example/app/
  App.java                 entry point, login flow
  db/                      H2 connection, transactions, schema migrations
  domain/                  records and enums (Money, Product, Sale, ...)
  repository/              SQL for each table
  service/                 business rules, permission checks, transactions
  payment/                 payment providers (cash, simulated card)
  ui/                      JavaFX screens and dialogs
  util/                    password hashing, CSV writing
src/main/resources/com/example/app/db/V1__init.sql   schema
src/test/java/...          unit and integration tests (in-memory H2)
```

## Known limits

- One store and one terminal. The H2 file must not be opened by two running copies of the app.
- Payments are simulated. A real card terminal or gateway would replace `SimulatedCardProvider`.
- Discounts apply per line. There is no whole-cart discount yet.
- Refunds to cards are recorded but not sent to a payment provider.
- Receipts can be saved as text. There is no direct printing.
- Store settings have no screen yet.
