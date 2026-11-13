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

Price-change history, price snapshots, and scheduled-task logs are stored together in SQLite. The path is shown in the task and price-history windows and can be set with `SETRETAILX_TASKS_DB_PATH` in `.env` (or the process environment); relative paths are resolved from the application directory. The default is the per-user `SetRetailX/printer12.db` location shared with the Python version. Restart the app after changing this path. In SetRetailX data, `pricesMap` is the ordinary-price collection and `prices99Map` is the collection named `99`; `number` values `1`–`4` identify configured price positions within either collection, not universally defined price types. Their business meanings depend on the SetRetailX configuration. In the price comparison, each scanned `priceN` is checked against both collections' same-number entry because the scan itself carries no regular/99 discriminator.

Barcode inputs in product search, price-history search, and scanner processing accept a plain barcode or a scanner payload. The shared `BarInput` parser recognizes `barcode=…` fields (including keys scanned with the Russian keyboard layout), `?`-separated price payloads, and GS1/Честный знак strings; it extracts the product GTIN from GS1 AI `01` and does not treat logistics SSCC AI `00` as a product barcode.

## Windows builds

The **Windows build** GitHub Actions workflow runs for pushes and pull requests to `main`, and can also be started manually from the Actions tab. Download either artifact from a successful workflow run:

- `SetRetailXPrinter-windows-installer` — Windows installer `.exe` (adds a Start menu entry and shortcut).
- `SetRetailXPrinter-windows-portable` — ZIP with the portable app folder and launcher `.exe`.

Both packages include a Java runtime. The workflow stages only the application JAR, so it does not bundle `.env`, credentials, or `all_products.sqlite`. Provide the catalog and SetRetailX connection settings separately when needed; never add real credentials or a private catalog to the workflow artifacts.

## Migration status

- Implemented: Swing/FlatLaf MDI workspace with independently movable internal windows and separate Java classes for the workspace and scanner, comparison, search, queue, and exchange screens; shared scanner routing, `ScannedProbe` parsing and price validation, asynchronous local catalog search, barcode lookup, queue, format and print-run assignment.
- In progress: server authentication and JSON-RPC client, actual price comparison and printing, print history, product cards, and persistent scheduled tasks.
- Python source is intentionally retained until the remaining behavior has been ported and verified.
