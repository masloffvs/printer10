# SetRetailX Printer — Java migration

The original Python application remains available as a behavior reference while the migration is in progress. The Java desktop application uses Swing with FlatLaf and keeps the current Python entry points intact.

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer
- The existing `all_products.sqlite` catalog in the project root for local search

## Run

```sh
mvn clean package
java -jar target/printer-1.0.0-SNAPSHOT.jar
```

On first launch the dark FlatLaf theme is installed before Swing components are created. SetRetailX credentials can be supplied through environment variables or a root `.env` file (same variable names as `.env.example`). `SETRETAILX_CONNECTION_RETRIES` configures how many times requests are retried after a detected closed-connection failure (default `5`; use `0` to disable retries).

## License verification

Before opening application databases, the app validates its license with the licensing server and binds first activation to the installation key and a machine fingerprint. Server leases are signed with Ed25519 and encrypted for the client using X25519/HKDF-SHA256/AES-256-GCM. Binary framing alone is not security; the cryptographic layer provides confidentiality, integrity, and server authentication. If the server is unavailable, the latest signed lease is accepted for at most four hours. The lease is renewed in the background; if it expires, the app closes without deleting local data.

For local development, see `license-server-local/README.md`. The `license-server-local/` directory is Git-ignored; its keys and no-TLS HTTP server are for local testing only and must not be exposed to a network. Configure the production URL and pinned public keys in `.env` using `SETRETAILX_LICENSE_URL`, `SETRETAILX_LICENSE_ENCRYPTION_KEY_FILE`, `SETRETAILX_LICENSE_VERIFY_KEY_FILE`, and `SETRETAILX_LICENSE_KEY`.

The temporary Windows CI artifacts are built with `-Plicense-disabled`, so they skip both startup license checks and background lease renewal. Standard builds keep license verification enabled. Remove this profile from `.github/workflows/windows-build.yml` to restore enforcement in Windows artifacts; do not distribute bypass builds as licensed production releases.

## Parent and child nodes

Set `SETRETAILX_NODE_ROLE=parent` on the single parent PC and `SETRETAILX_NODE_ROLE=child` on each child in `.env` (or the process environment). The parent is the intended source of truth; children initiate synchronization and can bind to only one parent identity. Discovery and encrypted peer transport use TCP/UDP port `45873`; allow it only on trusted private networks. The first encrypted connection displays the remote Ed25519 SHA-256 fingerprint and requires explicit approval; compare it with the other computer through a separate trusted channel. Identity, parent binding, and trust records are stored under `~/.setretailx/peer-mesh` (or the equivalent user home directory).

Synchronization uses a version-2 parent snapshot for initial additive bootstrap, followed by a separate version-1 domain change feed. The bootstrap imports missing catalog rows/references, sales-point definitions and shelf items, map fixtures, addresses, boxes, and stored-product rows; stable UUID keys identify sales points and map fixtures across databases. The change feed propagates inserts, updates, and deletions for addresses, boxes, stored products, sales points, shelf items, and map fixtures. A conflicting local edit is retained and recorded rather than overwritten. Catalog/reference edits and deletions are not yet included in the change feed. Synchronization never replaces SQLite files. Sales, receipts, print history, application settings, and credentials are excluded from snapshots.

Accounts are stored only on the parent in a separate per-user `~/.setretailx/accounts.sqlite` database with salted PBKDF2 password hashes. The first parent administrator is bootstrapped locally on first launch; account management is available on the parent. The versioned Parent API is shared by local and remote calls over the encrypted peer channel. Catalog lookup and price reads are available without login by default (and may be restricted when a session is supplied); sales and receipt queries require `SALES_READ`, and sending print jobs requires `SHARED_DATA_WRITE`. Account administration requires `ACCOUNT_ADMIN`. Parent-only SetRetailX credentials and scheduled price monitoring are not used on children. Peer connections require matching protocol version 2 and reject oversized frames and inflated payloads. Scanner credential payloads are strictly validated but are not an authentication mechanism and are not connected to login.

## UI layout rule

In MDI work windows, primary and frequent actions (including save, close, scan-session completion, and printing) belong in a persistent footer at the bottom of the window. Keep selection and filtering controls near the top, and let the central content area use the remaining space. Avoid placing a window's only primary action in a scrollable area.

## Print document style

Locally generated print documents are designed for black-and-white printers and quick reading on a shop floor. Use only black, white, and neutral grays (`#e4e4e4` cell emphasis, `#d9d9d9` table headers, `#ececec` group subtitles, `#bbbbbb` row rules, `#333333` secondary text); never use color fills, gradients, shadows, logos, icons, or decorative page borders. Prefer DejaVu Sans (including Cyrillic) and DejaVu Sans Mono for codes. Make document titles, section bars, key codes, and totals easy to scan; keep secondary metadata and footers small. Documents use a bold title, muted context, a solid black header rule, black section bars with white text, compact gray-headed tables with repeating headers, dynamically sized rows, and a thin-rule footer with source, legend/mark explanations, and page count. Product lists are grouped by storage box, repeat group labels when a group continues on a new page, and explain article, barcode, quantity and QR contents in the legend. Pallet and box labels offer a 2×4 large and 2×6 compact A4 grid; reduce type and QR size proportionally while keeping the code, product/address context, source and QR legend legible. Use larger margins for tables and tighter margins only for dense label grids. Planogram cells are equal-width within each shelf row, outlined in black with narrow gaps and a solid shelf beneath. Every special marker must have a short footer explanation. Third-party SetRetailX price-tag templates are rendered by the server and must be styled in that template system.

Price-change history, price snapshots, and scheduled-task logs are stored together in SQLite. The path is shown in the task and price-history windows and can be set with `SETRETAILX_TASKS_DB_PATH` in `.env` (or the process environment); relative paths are resolved from the application directory. The default is the per-user `SetRetailX/printer12.db` location shared with the Python version. Restart the app after changing this path. In SetRetailX data, `pricesMap` is the ordinary-price collection and `prices99Map` is the collection named `99`; `number` values `1`–`4` identify configured price positions within either collection, not universally defined price types. Their business meanings depend on the SetRetailX configuration. In the price comparison, each scanned `priceN` is checked against both collections' same-number entry because the scan itself carries no regular/99 discriminator.

Barcode inputs in product search, price-history search, and scanner processing accept a plain barcode or a scanner payload. The shared `BarInput` parser recognizes `barcode=…` fields (including keys scanned with the Russian keyboard layout), `?`-separated price payloads, and GS1/Честный знак strings; it extracts the product GTIN from GS1 AI `01` and does not treat logistics SSCC AI `00` as a product barcode.

## Windows builds

The **Windows build** GitHub Actions workflow runs for pushes and pull requests to `main`, and can also be started manually from the Actions tab. Download either artifact from a successful workflow run:

- `SetRetailXPrinter-windows-installer` — Windows installer `.exe` (adds a Start menu entry and shortcut).
- `SetRetailXPrinter-windows-portable` — ZIP with the portable app folder and launcher `.exe`.

Both packages include a Java runtime. The workflow stages only the application JAR, so it does not bundle `.env`, credentials, or `all_products.sqlite`. Provide the catalog and SetRetailX connection settings separately when needed; never add real credentials or a private catalog to the workflow artifacts.

## Migration status

- Implemented: Swing/FlatLaf MDI workspace with independently movable internal windows and separate Java classes for the workspace and scanner, comparison, search, queue, and exchange screens; shared scanner routing, `ScannedProbe` parsing and price validation, asynchronous local catalog search, barcode lookup, queue, format and print-run assignment.
- Verified locally with a JSON-RPC stub: receipt monitoring fetches the full check and validates its ID before saving; missing or mismatched checks fail the task without saving a receipt or advancing the watermark. The live SetRetailX `findCheck` response shape and a networked parent/child end-to-end run have not yet been verified. Catalog/reference edits and deletions are not yet part of the domain change feed.
- Python source is intentionally retained until the remaining behavior has been ported and verified.
