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

On first launch the dark FlatLaf theme is installed before Swing components are created. SetRetailX credentials can be supplied through environment variables or a root `.env` file (same variable names as `.env.example`).

## Windows builds

The **Windows build** GitHub Actions workflow runs for pushes and pull requests to `main`, and can also be started manually from the Actions tab. Download either artifact from a successful workflow run:

- `SetRetailXPrinter-windows-installer` — Windows installer `.exe` (adds a Start menu entry and shortcut).
- `SetRetailXPrinter-windows-portable` — ZIP with the portable app folder and launcher `.exe`.

Both packages include a Java runtime. The workflow stages only the application JAR, so it does not bundle `.env`, credentials, or `all_products.sqlite`. Provide the catalog and SetRetailX connection settings separately when needed; never add real credentials or a private catalog to the workflow artifacts.

## Migration status

- Implemented: Swing/FlatLaf MDI workspace with independently movable internal windows and separate Java classes for the workspace and scanner, comparison, search, queue, and exchange screens; shared scanner routing, `ScannedProbe` parsing and price validation, asynchronous local catalog search, barcode lookup, queue, format and print-run assignment.
- In progress: server authentication and JSON-RPC client, actual price comparison and printing, print history, product cards, and persistent scheduled tasks.
- Python source is intentionally retained until the remaining behavior has been ported and verified.
