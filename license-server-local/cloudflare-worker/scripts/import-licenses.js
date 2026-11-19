import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const workerDir = resolve(scriptDir, "..");
const licensesPath = resolve(scriptDir, "../../data/cloudflare-licenses.json");
const licenses = JSON.parse(await readFile(licensesPath, "utf8"));
const sqlString = (value) => `'${String(value).replaceAll("'", "''")}'`;
const rows = Object.entries(licenses);
if (!rows.length) throw new Error(`No license records found in ${licensesPath}`);

const statements = rows.map(([key, record]) => {
  if (!key || !["active", "revoked"].includes(record?.status)) {
    throw new Error(`Invalid license record for ${JSON.stringify(key)}; status must be active or revoked.`);
  }
  const deviceKey = record.devicePublicKey ? sqlString(record.devicePublicKey) : "NULL";
  const deviceId = record.deviceId ? sqlString(record.deviceId) : "NULL";
  const activatedAt = record.activatedAt ? sqlString(record.activatedAt) : "NULL";
  return `INSERT INTO licenses (license_key, status, device_public_key, device_id, activated_at)\n`
    + `VALUES (${sqlString(key)}, ${sqlString(record.status)}, ${deviceKey}, ${deviceId}, ${activatedAt})\n`
    + `ON CONFLICT(license_key) DO UPDATE SET status = excluded.status;`;
});

const tempDir = await mkdtemp(resolve(tmpdir(), "license-import-"));
const sqlPath = resolve(tempDir, "licenses.sql");
try {
  await writeFile(sqlPath, `${statements.join("\n\n")}\n`, { mode: 0o600 });
  const result = spawnSync("bunx", ["wrangler", "d1", "execute", "LICENSES", "--remote", "--file", sqlPath],
    { cwd: workerDir, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exitCode = result.status ?? 1;
} finally {
  await rm(tempDir, { recursive: true, force: true });
}
