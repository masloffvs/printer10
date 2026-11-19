import { mkdir, writeFile } from "node:fs/promises";
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const serverDir = dirname(fileURLToPath(import.meta.url));
const keysDir = join(serverDir, "keys");
const dataDir = join(serverDir, "data");
await mkdir(keysDir, { recursive: true, mode: 0o700 });
const encryption = generateKeyPairSync("x25519");
const signing = generateKeyPairSync("ed25519");
for (const [name, pair] of [["license-x25519", encryption], ["license-ed25519", signing]]) {
  await writeFile(join(keysDir, `${name}-private.pem`), pair.privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  await writeFile(join(keysDir, `${name}-public.pem`), pair.publicKey.export({ type: "spki", format: "pem" }), { mode: 0o600 });
}
const licenseKey = randomBytes(24).toString("base64url");
await mkdir(dataDir, { recursive: true, mode: 0o700 });
await writeFile(join(dataDir, "licenses.json"), `${JSON.stringify({ [licenseKey]: { status: "active" } }, null, 2)}\n`, { mode: 0o600 });
console.log("Generated local development keys and one active license.");
console.log(`SETRETAILX_LICENSE_KEY=${licenseKey}`);
console.log("Set these paths in the app .env (relative to the repository root):");
console.log("SETRETAILX_LICENSE_URL=http://127.0.0.1:8097/v1/lease");
console.log("SETRETAILX_LICENSE_ENCRYPTION_KEY_FILE=license-server-local/keys/license-x25519-public.pem");
console.log("SETRETAILX_LICENSE_VERIFY_KEY_FILE=license-server-local/keys/license-ed25519-public.pem");
