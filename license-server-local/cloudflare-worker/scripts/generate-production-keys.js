import { generateKeyPairSync, randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const keysDir = resolve(scriptDir, "../../production-keys");
const dataDir = resolve(scriptDir, "../../data");
await mkdir(keysDir, { recursive: true, mode: 0o700 });
await mkdir(dataDir, { recursive: true, mode: 0o700 });

for (const [name, type] of [["license-x25519", "x25519"], ["license-ed25519", "ed25519"]]) {
  const pair = generateKeyPairSync(type);
  await writeFile(resolve(keysDir, `${name}-private.pem`), pair.privateKey.export({ type: "pkcs8", format: "pem" }),
    { mode: 0o600, flag: "wx" });
  await writeFile(resolve(keysDir, `${name}-public.pem`), pair.publicKey.export({ type: "spki", format: "pem" }),
    { mode: 0o644, flag: "wx" });
}

const licenseKey = randomBytes(24).toString("base64url");
await writeFile(resolve(dataDir, "cloudflare-licenses.json"), `${JSON.stringify({ [licenseKey]: { status: "active" } }, null, 2)}\n`,
  { mode: 0o600, flag: "wx" });
console.log("Created production keys and an initial active license (files are local-only).");
console.log(`SETRETAILX_LICENSE_KEY=${licenseKey}`);
console.log("SETRETAILX_LICENSE_URL=https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/v1/lease");
console.log("SETRETAILX_LICENSE_ENCRYPTION_KEY_FILE=license-server-local/production-keys/license-x25519-public.pem");
console.log("SETRETAILX_LICENSE_VERIFY_KEY_FILE=license-server-local/production-keys/license-ed25519-public.pem");
