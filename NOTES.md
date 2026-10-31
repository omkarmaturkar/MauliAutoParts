# Mauli Auto Parts — developer notes

Billing, stock and purchase app for a two & four wheeler spare parts shop.
Works fully offline. Written for one shopkeeper on one phone.

Last updated: 31 October 2026

---

## 1. How it is built

| Thing | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3). No XML layouts anywhere. |
| Database | Room, database name `mauli_auto_parts.db` |
| Navigation | Hand written. A stack of `Screen` objects in `AppViewModel`. No Navigation library. |
| Min / target SDK | 26 / 35 |
| Build | AGP 8.7.3, Kotlin 2.0.21, Gradle 8.10.2, Java 17 |
| APK build | GitHub Actions, `.github/workflows/build.yml`, run by hand from the Actions tab |

There is one module, `app`, and one package, `com.mauli.autoparts`. All Kotlin
files sit in `app/src/main/java/com/mauli/autoparts/`.

### Adding a new screen

1. Make the screen object. A screen that keeps the bottom bar is
   `data object MyPage : Screen()`. A screen that opens on top of the tabs
   (back arrow, no bottom bar) is `data object MyPage : Screen(), LeafScreen`.
2. Add it to the `when (s)` block in `MainActivity.kt` → `AppBody`.
3. Open it with `vm.open(MyPage)` for a leaf screen, or `vm.tab(MyPage)` for a tab.

`LeafScreen` is declared in `Menu.kt`.

---

## 2. The database

Three tables only: `parts`, `bills`, `bill_items`.

Everything that is not a part is a row in `bills`, told apart by its `kind`:

| kind | What it is | How the columns are used |
|---|---|---|
| 0 | Sale bill (an estimate when `isEstimate = true`) | normal |
| 1 | Purchase from a vendor | `customer` = vendor, `phone` = vendor phone, `ref` = vendor's bill no. |
| 2 | Saved services list (hidden) | id `-2`, `note` holds the package ids |
| 3 | A saved package (hidden) | ids from `-101` downwards |
| 4 | A saved vendor (hidden) | `customer` = name, `phone` = mobile, `ref` = GSTIN, `note` = address. ids from `-1000001` downwards |
| 5 | Job card | `note` = complaint, `ref` = status, `payMode` = mechanic, `total` = estimate, `paid` = advance |

**Why hidden rows instead of new tables:** a backup saves every row of these
three tables, so services, packages and vendors travel with every backup
without any extra code. Negative ids keep them away from real bills.

**Never show kinds 2–5 in sales lists.** `billsFlow()` already filters to
`kind = 0`, `purchasesFlow()` to `kind = 1`.

### Database versions

- v1 → v2: added `kind` and `ref` to `bills`
- v2 → v3: added `listPrice` and `discPct` to `bill_items` (vendor discount)

Current version is **3**. Changing any `@Entity` means a new version number and
a new `Migration` in `Data.kt`, or the app will crash on existing installs.

---

## 3. What each file does

### Core
- **`Data.kt`** — all Room tables, the DAO, migrations, `ShopSettings`, `Draft`,
  money and date helpers, JSON for backups, CSV helpers.
- **`AppViewModel.kt`** — the whole app's state: screen stack, settings, open
  bills (drafts), saving bills and purchases, stock maths, backup/restore.
- **`MainActivity.kt`** — the single Activity, the screen list, the bottom bar.
- **`Ui.kt`** — colours, theme, and every shared piece: `AppCard`, `AppField`,
  `ScreenHeader`, `ChipRow`, `Segmented`, dialogs. **Start here before writing
  any new UI**; the piece you need probably exists.
- **`Logo.kt`** — the logo, drawn in code, and the opening splash.

### Screens
- **`HomeScreen.kt`** — dashboard, today's sale, menu tiles, low stock, recent bills.
- **`Menu.kt`** — the Menu tab and the Home tile grid. `LeafScreen` lives here.
- **`StockScreen.kt`** — parts list, filters, the quick "Add stock" box.
- **`PartScreen.kt`** — add / edit one part.
- **`BillingScreen.kt`** — the billing screen. Several bills can be open at once.
- **`BillsScreen.kt`** — saved bills, and the bill view with the on-screen invoice.
- **`PurchaseScreen.kt`** — purchase list and purchase entry (per-item discount %).
- **`VendorScreen.kt`** — vendor list and one vendor with all its bills.
- **`JobCardScreen.kt`** — job cards: vehicle in, status, then make a bill.
- **`VehicleHistoryScreen.kt`** — every vehicle and all its past visits.
- **`ReportsScreen.kt`** — sales, profit, payment modes, top parts, stock value.
- **`SettingsScreen.kt`** — shop details, GST, printer, backup.
- **`ImportScreen.kt`** — the Excel / CSV import screen with column mapping.

### Output and data
- **`Invoice.kt`** — the A4 PDF and the thermal (ESC/POS) bill, BUSY style.
  `SHOP_OWNER` is **hardcoded here** — see pending work.
- **`Printer.kt`** — Bluetooth printing, sharing, WhatsApp, QR codes, money
  and date formatting used everywhere. Its own `billPdf` and `escPosBill` are
  **dead code**, replaced by `Invoice.kt`.
- **`ExcelImport.kt`** — reads .xlsx and CSV, guesses the columns, saves parts.
- **`Catalog.kt`** — saved services and packages for fast billing.
- **`DriveBackup.kt`** — Google Drive backup, daily WorkManager job, restore.

---

## 4. Rules to keep

- **Buy price, vendor discount and profit are for the owner only.** They must
  never appear on a customer bill, PDF, print or WhatsApp message.
- **Stock changes only inside a Room transaction**, with a stock check first.
  See `saveBill` and `savePurchase` in `AppViewModel.kt`.
- **Open bills reserve stock.** `vm.reserved()` and `vm.limitFor(part)` stop the
  same part being sold twice across two open bills.
- **The app must keep working with no internet.** Only Drive backup needs it.
- **Plain language in the UI**, no jargon. The user is a shopkeeper, not a
  developer. "Udhaar", not "credit ledger".
- Prices are **GST inclusive**. A discount is spread across items in proportion.

---

## 5. What was added on 31 October 2026

The client asked for a menu, purchases split from stock, a vendor discount, and
vendors with their bills. Built in five rounds:

1. **Menu** — new Menu tab (took the Reports slot in the bottom bar), menu tiles
   on Home, Vehicle History, and a one-tap Low stock shortcut.
2. **Purchases split from Stock** — purchase shortcuts removed from the Stock
   screen; a vendor discount % added per purchase line (database v3).
3. **Vendors** — create vendors, tap one for all its bills by date, pay a
   vendor, rename a vendor (its old purchases get renamed too).
4. **Job cards** — vehicle in, complaint, status Received → Working → Ready →
   Delivered, then "Make bill".
5. **Excel import rebuilt** — reads .xlsx directly, works out which column is
   the part number, name and price, lets the user correct any column, shows a
   preview, then imports. Matches on part number first, then name, so
   re-importing updates instead of duplicating.

Also fixed the same day: the purchase screen now has an **Add parts** button
that lists the whole stock (before, a part could only be found by searching),
and each line has its own rate and discount %, instead of one % for all.

---

## 6. Still to do

### Security — do this before handing the app over
The release signing key and its password are **in the public repo**:
`app/mauli-release.jks`, and the password `mauli2026` in plain text in both
`.github/workflows/build.yml` and `app/build.gradle.kts`.

Anyone can download the key and sign an APK that Android will accept as an
update to this app. The fix:
1. Put the keystore (base64) and the passwords in GitHub Secrets, and have the
   workflow decode them at build time.
2. Remove the key and the passwords from the repo, or make the repo private.
3. If the app has already been shared, the key has to be replaced, which means
   existing installs must be uninstalled before the new APK will install.

### Features worth building next
- **Service reminders** — next service due by date or KM, a "Due soon" list on
  Home, a one-tap WhatsApp reminder. The strongest idea taken from the mVaaHna
  app the client compared this to. Brings customers back.
- **Purchases in Reports** — Reports covers sales only right now.
- **Billing: type a full part number and it adds itself**, with no tap. Useful
  at a busy counter.

### Cleanups
- `SHOP_OWNER` in `Invoice.kt` is hardcoded to one name. It belongs in
  `ShopSettings` so any shop can use the app.
- `Printer.billPdf` and `Printer.escPosBill` are dead code; `Invoice.kt`
  replaced them.
- Restoring from Google Drive does not clear the open bill drafts, while the
  file restore in `AppViewModel.restore` does. Stale drafts can point at parts
  that no longer exist.
- Local builds always get `versionCode = 1`, because the number comes from
  `GITHUB_RUN_NUMBER`.

---

## 7. Building the APK

Actions tab → "Build Mauli Auto Parts APK" → Run workflow. The APK comes out as
an artifact named `MauliAutoParts-APK`.

If the build fails, the real cause is a line starting with `e: file:///...`
inside the "Build APK" step. Search the log for `e: file` — the stack trace at
the bottom is not the error.

**Common cause:** pasting into the GitHub editor without selecting all the old
text first, which leaves half the old file behind and gives
`Expecting '}'`. Always Ctrl+A, delete, then paste.

---

## 8. Before changing anything

1. Take a backup from inside the app: Settings → Save backup file.
2. If an `@Entity` changes, bump the database version **and** write the migration.
3. Build and install, then check: an old bill opens, stock totals look right,
   and a backup can be restored.
