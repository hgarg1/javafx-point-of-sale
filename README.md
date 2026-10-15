<p align="center">
  <img src="docs/banner.svg" alt="Point of Sale for JavaFX: checkout, refunds and voids, shifts, purchasing and reports" width="100%">
</p>

<h1 align="center">Point of Sale for JavaFX</h1>

<p align="center">
  <strong>A desktop cashier and inventory system for one store and one till.</strong><br>
  Checkout, held sales, refunds and voids, shifts, purchasing, reports and an audit trail. All data lives in an embedded
  H2 database on the same computer.
</p>

<p align="center">
  <img alt="Java 26" src="https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white">
  <img alt="JavaFX 26" src="https://img.shields.io/badge/JavaFX-26.0.2-2C5F9E">
  <img alt="H2 2.5" src="https://img.shields.io/badge/H2-2.5.252-1B6AC9">
  <img alt="Maven 3.9 or newer" src="https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white">
  <img alt="Tested on Windows 11" src="https://img.shields.io/badge/tested%20on-Windows%2011-0078D4?logo=windows&logoColor=white">
</p>

<p align="center">
  <a href="#features">Features</a> &nbsp;·&nbsp;
  <a href="#screenshots">Screenshots</a> &nbsp;·&nbsp;
  <a href="#quick-start">Quick start</a> &nbsp;·&nbsp;
  <a href="#how-a-sale-works">How a sale works</a> &nbsp;·&nbsp;
  <a href="#architecture">Architecture</a> &nbsp;·&nbsp;
  <a href="#testing">Testing</a> &nbsp;·&nbsp;
  <a href="#known-limits">Known limits</a>
</p>

---

## Features

| | Feature | What it does |
|:--:|:--|:--|
| 🛒 | **Fast checkout** | Scan a barcode, or type a SKU or name. One sale can be paid with several payment types, and change is worked out for you. |
| ⏸️ | **Held sales** | Park a cart while you serve someone else. Recalling it prices every line again, checks stock, and merges it into the cart on screen. |
| ↩️ | **Refunds and voids** | Refund items back to the payment type that was used, up to what it paid. Cashiers need a manager's approval to refund. A whole sale can be voided from the open shift, with a manager's approval. |
| 🕒 | **Shifts** | One open shift per terminal, a blind cash count at close, and an end-of-shift report you can save. |
| 🏷️ | **Payment types** | Add, rename, reorder and switch off payment types in Settings. Cash is always available. |
| 📦 | **Stock and purchasing** | Every stock change is a ledger movement. Purchase orders take partial deliveries, costs are weighted by quantity, and reorder suggestions are grouped by supplier. |
| 📊 | **Reports** | A dashboard, a sales list, a running ledger, a filterable audit log, and CSV exports. |
| 🔐 | **Roles and audit** | Cashier, manager and admin roles, checked in the service layer. Important actions are written to an audit log. |
| 🎨 | **Light and dark** | Follows your system theme, with compact, comfortable and touch-screen densities. |
| 💾 | **Backups** | Automatic backups on exit and before any upgrade, plus a Back up now button. |

Every screen and dialog shares the sign-in page's warm ivory, olive and charcoal palette. Buttons have smooth hover and pressed states; animations respect the operating system's reduced-motion preference. Calendar pickers, menus and table editors use the same theme in light and dark mode.

Drag the dividers to adjust the sidebar, checkout panes, product editor, settings cards and stock or purchasing sections. Controls wrap in smaller windows, forms give inputs room to grow, and refund quantities stay linked to the correct item after sorting. Typed report dates are validated before running a report.

The Shift workspace has a panoramic retail header, prominent sales totals, and adjustable payment-activity and drawer-count panels. It supports opening-float presets and keeps the expected closing cash hidden until the independent count is submitted.

Reports groups the date range separately from its CSV export menu and arranges dashboard metrics in an ordered, evenly sized grid. The header identifies the signed-in user with an initials avatar and separate username and role labels.

## Screenshots

<p align="center"><sub>The screenshots are rendered by the test suite from invented demo data. No real customers or products appear here.</sub></p>

| Screen | Light | Dark |
|:--|:--:|:--:|
| **Sign in** | <a href="docs/screenshots/login-light.png"><img src="docs/screenshots/login-light.png" alt="Sign in with daylight retail artwork" width="360"></a> | <a href="docs/screenshots/login-dark.png"><img src="docs/screenshots/login-dark.png" alt="Sign in with evening retail artwork" width="360"></a> |
| **Checkout** | <a href="docs/screenshots/checkout-light.png"><img src="docs/screenshots/checkout-light.png" alt="Checkout, light theme" width="360"></a> | <a href="docs/screenshots/checkout-dark.png"><img src="docs/screenshots/checkout-dark.png" alt="Checkout, dark theme" width="360"></a> |
| **Reports** | <a href="docs/screenshots/reports-light.png"><img src="docs/screenshots/reports-light.png" alt="Reports, light theme" width="360"></a> | <a href="docs/screenshots/reports-dark.png"><img src="docs/screenshots/reports-dark.png" alt="Reports, dark theme" width="360"></a> |
| **Take payment** | <a href="docs/screenshots/payment-light.png"><img src="docs/screenshots/payment-light.png" alt="Payment dialog, light theme" width="360"></a> | <a href="docs/screenshots/payment-dark.png"><img src="docs/screenshots/payment-dark.png" alt="Payment dialog, dark theme" width="360"></a> |
| **Payment retry** | <a href="docs/screenshots/payment-error-light.png"><img src="docs/screenshots/payment-error-light.png" alt="Payment error with input preserved, light theme" width="360"></a> | <a href="docs/screenshots/payment-error-dark.png"><img src="docs/screenshots/payment-error-dark.png" alt="Payment error with input preserved, dark theme" width="360"></a> |
| **Settings: payment types** | <a href="docs/screenshots/settings-light.png"><img src="docs/screenshots/settings-light.png" alt="Settings, light theme" width="360"></a> | <a href="docs/screenshots/settings-dark.png"><img src="docs/screenshots/settings-dark.png" alt="Settings, dark theme" width="360"></a> |

<details>
<summary><b>More screens</b></summary>

<br>

| Products | Purchasing |
|:--:|:--:|
| <a href="docs/screenshots/products-light.png"><img src="docs/screenshots/products-light.png" alt="Products" width="420"></a> | <a href="docs/screenshots/purchasing-light.png"><img src="docs/screenshots/purchasing-light.png" alt="Purchasing" width="420"></a> |
| **Refunds** | **Shift** |
| <a href="docs/screenshots/refunds-light.png"><img src="docs/screenshots/refunds-light.png" alt="Refunds" width="420"></a> | <a href="docs/screenshots/shift-light.png"><img src="docs/screenshots/shift-light.png" alt="Shift" width="420"></a> |
| **Stock** | **Users** |
| <a href="docs/screenshots/stock-light.png"><img src="docs/screenshots/stock-light.png" alt="Stock" width="420"></a> | <a href="docs/screenshots/users-light.png"><img src="docs/screenshots/users-light.png" alt="Users" width="420"></a> |
| **First-time setup** | **Receipt** |
| <a href="docs/screenshots/setup-light.png"><img src="docs/screenshots/setup-light.png" alt="Create owner account" width="420"></a> | <a href="docs/screenshots/receipt-light.png"><img src="docs/screenshots/receipt-light.png" alt="Receipt" width="420"></a> |

</details>

## Native installers

Self-contained installers bundle Java and JavaFX, with separate normal and demo applications. Local build scripts target Windows 11 x64 (`.exe`), macOS Intel/Apple Silicon (`.dmg`), and Ubuntu 24.04 x64 (`.deb`). Each must be built on its native OS. These unsigned packages are for private testing.

See [installer build and installation instructions](packaging/README.md) and [platform verification status](packaging/VERIFICATION.md). Store data stays in the existing user-home folders and survives uninstall or upgrade.

## Quick start

**You need**

- **JDK 26**. The version is set in `pom.xml` as `maven.compiler.release`. Change it if you use another JDK.
- **Apache Maven 3.9** or newer.

```bash
git clone https://github.com/hgarg1/javafx-point-of-sale.git
cd javafx-point-of-sale
mvn javafx:run
```

The first start shows a setup screen. Create the **owner account** there. That is the only account the setup makes. As the
owner, open **Users** and add the staff. New accounts get a temporary password and choose their own at first login. There is
no default password.

To try the app with sample products, sales, purchasing and staff accounts, run `mvn -Pdemo javafx:run`.
The window is marked **[DEMO]**, and its database, preferences, logs and backups live separately under
`%USERPROFILE%\.cashier-app-demo`. The sample data is loaded only on the first demo launch; later launches keep your
demo changes. Normal startup continues to use your existing store.

| Demo role | Username | Password |
|:--|:--|:--|
| Admin | `owner` | `owner-pass-1` |
| Manager | `maria` | `maria-pass-1` |
| Cashier | `sam` | `sam-pass-1` |

Sign-in and setup use a shared responsive layout with generated daylight and evening retail artwork, labeled fields,
inline errors, and light/dark palettes. Sign-in includes Show/Hide password. Artwork is bundled locally, so no network
connection is required; smaller layouts hide it to keep the form usable. Image-generation prompts are recorded in
`src/main/resources/com/example/app/ui/artwork/artwork-prompts.txt`.

<details>
<summary><b>Where things are</b></summary>

<br>

| What | Where (Windows; other systems use your home folder in the same way) |
|:--|:--|
| Database | `%USERPROFILE%\.cashier-app\data\pos.mv.db` |
| Backups | `%USERPROFILE%\.cashier-app\backups` |
| Log files (up to five, about 1 MB each) | `%USERPROFILE%\.cashier-app\logs` |

</details>

## How a sale works

```mermaid
sequenceDiagram
    autonumber
    actor Cashier
    participant Till as Checkout screen
    participant Svc as CheckoutService
    participant Pay as Payment providers
    participant DB as Database
    Cashier->>Till: Scan items, then press F9
    Till->>Svc: checkout(cart, payments, approver)
    Svc->>Svc: Price the cart, read only
    Svc->>Svc: Check each payment type is on and has its reference
    Svc->>Svc: Split the total, non-cash first and cash last
    Svc->>Pay: Authorize each payment
    Svc->>DB: Write the sale, its payments and stock in one transaction
    alt Anything fails
        Svc->>Pay: Release every authorization
    end
    Svc-->>Till: Receipt
```

- Card, gift card and QR or wallet payments are taken first, each up to what is still owed. Cash comes last, so it only covers
  the rest and gives change.
- If a sale cannot be written, every authorization taken for it is released, so no hold outlives a failed sale.
- Payments and refunds keep the name the payment type had when they happened, so old receipts still read correctly.
- Discounts above the store's approval level (10% by default) need a manager. The approval is asked for when the discount
  is applied, not at payment.

<details>
<summary><b>Daily use, step by step</b></summary>

<br>

1. **Shift.** Open a shift with the cash float. The terminal has one open shift at a time, and every sale and refund is
   recorded on it. To hand over, count and close the shift, then open the next one. A manager can close a shift that was
   left open.
2. **Checkout.** Scan a barcode or type a SKU or name, then press Enter. Double-click a search result to add it. Select a
   cart line to change its quantity or discount.
3. **Payment (F9).** Enter one or more payments, one row for each payment type that is turned on. Cash is taken last, so it
   only covers what is left, and cash handed over above the total is given back as change. The payment types are:
   - **Cash** goes into the drawer. Quick amounts are offered.
   - **Card** is simulated. A card number ending in `0002` is declined.
   - **QR or wallet** needs the payment's reference. It does not go into the drawer.
   - **Gift card or store credit** needs the card's code, and the balance must cover the amount.
   - A payment type added in Settings takes the behaviour of one of these four kinds.
4. **Holding a sale.** F6 parks the whole cart with an optional label, so you can serve someone else. Nothing is reserved
   while a sale is held. F7 lists held sales with their label, cashier, how long each has waited, line count and total at
   today's prices. Pick one to recall it, or discard it from the same list. The header shows how many sales are held.
   Recalling into a cart that already has items merges the held lines into it. A held line for a product already on the
   cart adds its quantity to that line, and the line takes the higher of the two discounts. A manager must approve it if
   that discount is above the approval level. Any other held line is added as its own line. Recalled lines are priced again
   at today's prices, and stock is checked on the merged quantities. Anything that can no longer be sold as it stands is
   listed before you take payment. If the merged sale would exceed the 200-line limit, or a line would exceed 100,000 units,
   the recall is refused and the held sale stays on the list.
5. **Gift cards.** A gift card product sells at face value for each unit. It carries no tax and is not counted as stock.
   Each unit prints a card code on the receipt. A refund paid to a gift card or store credit goes back onto that card.
6. **Refunds.** Look up the receipt, choose the items and quantities, and choose the tender to pay back to. Each tender can
   be refunded only up to what it paid. Cashiers need a manager's approval. Items can go back into stock.
7. **Voids.** On **Reports**, open the **Sales** tab, select a sale from the open shift, and choose **Void sale**. A void
   reverses the whole sale: items still on it go back into stock, and what is still owed goes back to each tender. A reason
   is required. A void is refused for a sale from an earlier shift, a sale that issued gift cards, a sale with no payment,
   and a sale that is already fully refunded. Voids are counted apart from refunds on the dashboard, in the shift report and
   in the ledger.
8. **Shift close.** Count the drawer before seeing the expected cash. The result shows the variance, and the end-of-shift
   report can be saved as text.

</details>

## Keyboard shortcuts

| Key | Where | Action |
|:--|:--|:--|
| `F1` | Anywhere | Show the shortcut list |
| `Ctrl+1` to `Ctrl+9` | Anywhere | Switch screen, in sidebar order |
| `F11` | Anywhere | Full screen |
| `F2` | Checkout | Focus the scan and search box |
| `Enter` | Scan box | Add the scanned barcode or SKU, or the single search match |
| `F6` | Checkout | Hold the current sale |
| `F7` | Checkout | Recall a held sale |
| `F9` | Checkout | Take payment |

A barcode scanner types the code and presses Enter, so it works in the scan box like the keyboard. For a touch screen, choose
the *Touch screen* density in Settings to make buttons and fields larger.

Checkout keeps searches with multiple matches or no matches visible. Use Down to focus the results, Enter to add the
selected product, and Escape to return to the full product list. Stock problems appear beside the totals and prevent
payment until the cart is corrected. Availability is checked again when the sale completes.

If a payment is declined or checkout fails, the payment dialog stays open with the entered amounts and references.
Correct them and retry, or cancel to return to the cart. Cancelling manager approval also leaves payment open.

A successful sale clears the cart and returns to scanning. **View last receipt** opens the latest receipt for viewing
or saving as text; receipts no longer open automatically. Gift-card sales show a reminder to give the customer their
codes from the receipt. The latest receipt is retained only for the current login; older receipts remain in Reports.

Product drafts are protected when changing selection or filters, starting a new product, leaving the screen, logging
out, or closing the window. Choose Save, Discard, or Cancel. A failed save keeps the draft open. Drafts remain in memory,
including while the screen is idle-locked. Dashboard cards and report controls wrap at smaller window sizes, and the
dashboard scrolls vertically when needed.

## Roles

| What | Cashier | Manager | Admin |
|:--|:--:|:--:|:--:|
| Checkout, hold and recall | yes | yes | yes |
| Shift | yes | yes | yes |
| Refunds | yes, with a manager's approval | yes | yes |
| Products and stock | | yes | yes |
| Purchasing | | yes | yes |
| Reports, voids, ledger, audit log, CSV exports | | yes | yes |
| Users | | | yes |
| Settings | | | yes |

A manager's approval means entering a manager's username and password in the dialog that appears. Permission checks run in
the service layer, not only in the screens.

## Architecture

```mermaid
flowchart LR
    UI["JavaFX screens<br/>and dialogs"] -->|calls| SVC["Services<br/>rules, permissions, transactions"]
    SVC -->|uses| REPO["Repositories<br/>plain JDBC"]
    REPO --> DB[("H2 file database<br/>migrations V1 to V6")]
    SVC -->|authorizes through| PAY["Payment providers<br/>cash, card, QR or wallet, gift card"]
```

| Layer | Package | May use |
|:--|:--|:--|
| Screens and dialogs | `ui/` | services only |
| Business rules | `service/` | repositories and payment providers |
| Data access | `repository/` | the database |
| Money and records | `domain/` | nothing else |

`ArchitectureTest` checks these rules on every build, so a screen that reaches past the services fails the tests.

<details>
<summary><b>Money and stock rules</b></summary>

<br>

- Money is stored as whole cents, and every amount is rounded half-up to the cent. Tax rates are stored in basis points
  (`800` is 8%). Tax is worked out line by line, after any discount, so receipts add up exactly.
- Sales, payments, refunds, voids and stock movements are append-only. Corrections are new rows.
- Every stock change is a movement row, and the quantity on a product is the total of those rows. Stock changes only through
  the stock ledger: sales, refunds, voids, deliveries booked against an order (each points at its receipt), and the Stock
  screen's receive, adjust, waste and stock-take actions.
- A refund never returns more than was paid or sold. The last unit of a line is refunded for the exact remaining amount, so
  partial refunds never lose a cent.
- Card and other provider holds are released if a sale cannot be written, so no hold outlives a failed sale.

</details>

<details>
<summary><b>Purchasing in detail</b></summary>

<br>

Managers use **Purchasing**. An order goes through these steps:

- **Draft.** Start an order with a supplier and an optional note. Add items and their unit costs, or remove them.
- **Ordered.** Send the order. Its items can no longer change.
- **Part received, or Received.** Book in a delivery. Each item can arrive in part, and an optional invoice number is
  recorded. A cost can be corrected for the delivery. You cannot receive more than was ordered.
- **Cancelled.** Possible only while nothing has arrived.

Stock from a supplier enters through deliveries booked against an order. Each delivery is a RECEIVE movement that points at
its receipt. Stock that arrives without an order is recorded on the Stock screen. The average cost of the product is updated
with each delivery, weighted by quantity. If there is nothing on hand, or the count is negative, the delivery's cost becomes
the new cost. Cost changes are written to the audit log.

**Reorder suggestions** lists products at or below their reorder level, grouped by supplier. The quantity is the product's
*order quantity* if it has one, otherwise enough to bring the count back to twice the reorder level. Products with no
supplier are listed but not ordered until a supplier is set. **Draft orders from these** creates one draft per supplier.
Products already on a draft, ordered or part-received order are left out, so drafting twice does not duplicate orders. Any
order can be exported as CSV to send to the supplier.

</details>

<details>
<summary><b>Settings, backups and restore</b></summary>

<br>

Admins open **Settings**:

- **Store:** store name, currency, number format, the discount level that needs manager approval, and the receipt footer.
  The currency cannot be changed after the first sale.
- **Payment types:** add a payment type of one of the four kinds, rename it, turn it off, change its place in the payment
  list, and choose whether it counts in the drawer. Cash cannot be turned off or deleted, and a cash-kind type always counts
  in the drawer. A type that has been used on a sale or refund cannot be deleted; turn it off instead. A type that is off is
  not offered at the till, but refunds to sales already paid with it still work. Whether a type counts in the drawer can only
  change while no shift is open, because it changes the expected cash.
- **Appearance:** colour scheme (follows the system by default), density, and the idle-lock time in minutes. Zero turns the
  lock off. A new idle time applies from the next login.
- **Data:** *Check stock figures* compares each product's count with its movement history. *Back up now* writes a backup on
  demand.

Backups go to the backups folder. The app makes one automatically on exit when the last backup is more than 24 hours old,
and before any upgrade of existing data. The ten most recent are kept.

To restore a backup, close the app, open the backup's `.zip` file, and copy the file ending in `.mv.db` inside it over
`%USERPROFILE%\.cashier-app\data\pos.mv.db`. Then start the app.

Store settings are kept in the `settings` table:

| Key | Default | Meaning |
|:--|:--|:--|
| `store_name` | My Store | Shown on the login screen and receipts |
| `currency` | USD | Currency code, locked after the first sale |
| `locale` | en-US | Number format |
| `allow_negative_stock` | false | Allow selling more than is on hand |
| `discount_approval_threshold_bp` | 1000 | Line discounts above this need manager approval |

</details>

## Testing

```bash
mvn test                   # the test suite, against in-memory H2 databases
mvn test -Dscreens=true    # also renders every screen and the key dialogs to target/screens, in light and dark
```

The suite covers the money and stock rules: refunds, voids, payment types, held sales, shifts, purchasing, login throttling,
backups, and migrations, including upgrading a database that already holds data. Screenshot tests are switched off unless
`-Dscreens=true` is given.

<details>
<summary><b>Project layout</b></summary>

<br>

```
src/main/java/com/example/app/
  App.java                 entry point, first-run and login flow, backup on exit
  db/                      H2 connection, transactions, schema migrations
  domain/                  records and enums (Money, Product, Sale, PurchaseStatus, ...)
  repository/              SQL for each table
  service/                 business rules, permission checks, transactions
                           (Pricing and PaymentCoordinator are the pure parts of checkout)
  payment/                 payment providers: cash, simulated card, external, stored value
  error/                   PosException and the rules the screens show as they are
  ui/                      JavaFX screens, dialogs and the theme
  util/                    password hashing, CSV writing, logging
src/main/resources/com/example/app/db/V1__init.sql ... V6__tender_configuration.sql   schema migrations
src/main/resources/com/example/app/ui/theme.css                                       the light and dark theme
src/test/java/...          unit and integration tests (in-memory H2), plus ArchitectureTest
docs/                      this page's banner and screenshots
```

</details>

## Known limits

- Developed and tested on Windows 11 only.
- One store and one terminal. The H2 file must not be opened by two running copies of the app.
- Payments are simulated. Card and QR or wallet tenders are recorded, not sent to a real terminal or gateway.
- The four payment kinds (cash, card, gift card or store credit, QR or wallet) are fixed in the code. A new kind of payment
  needs a code change; new named payment types of an existing kind do not.
- Discounts apply per line. There is no whole-cart discount yet.
- Receipts can be saved as text (`.txt`). There is no printing yet.
- Backups are restored by copying the database file back, with the app closed. There is no restore button.
- Product CSV import is not included.
- A void that pays back more than one tender is not atomic with the payment providers. If a real provider's refund failed
  after an earlier one succeeded, the earlier refund would need reconciling by hand. Only simulated providers exist here.

## Built with

[JavaFX](https://openjfx.io/) 26.0.2 · [H2](https://www.h2database.com/) 2.5.252 · [JUnit](https://junit.org/junit5/) 6.1.3 ·
[Maven](https://maven.apache.org/). Icons come from [Lucide](https://lucide.dev/) under the ISC licence. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License

No license has been chosen yet, so the code is not licensed for reuse until one is added.
