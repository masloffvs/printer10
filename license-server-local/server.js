import { createCipheriv, createDecipheriv, createHmac, createPrivateKey, createPublicKey,
  diffieHellman, hkdfSync, randomBytes, sign, verify } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const serverDir = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 8097);
const host = process.env.HOST || "127.0.0.1";
const encryptionPrivatePath = resolve(process.env.LICENSE_ENCRYPTION_PRIVATE_KEY || join(serverDir, "keys/license-x25519-private.pem"));
const signingPrivatePath = resolve(process.env.LICENSE_SIGNING_PRIVATE_KEY || join(serverDir, "keys/license-ed25519-private.pem"));
const licensesPath = resolve(process.env.LICENSES_FILE || join(serverDir, "data/licenses.json"));
const leaseSeconds = 4 * 60 * 60;
const maxPacketSize = 64 * 1024;

function binary(bytes, status = 200) {
  return new Response(bytes, { status, headers: { "content-type": "application/octet-stream",
    "cache-control": "no-store", "x-content-type-options": "nosniff" } });
}

function decodePacket(packet) {
  if (packet.length < 1 + 16 + 12 + 2 + 16 || packet[0] !== 1) throw new Error("bad packet");
  const salt = packet.subarray(1, 17);
  const nonce = packet.subarray(17, 29);
  const keyLength = packet.readUInt16BE(29);
  const keyEnd = 31 + keyLength;
  if (keyLength < 32 || keyLength > 512 || keyEnd + 16 > packet.length) throw new Error("bad key length");
  return { salt, nonce, ephemeralKey: createPublicKey({ key: packet.subarray(31, keyEnd), format: "der", type: "spki" }),
    ciphertext: packet.subarray(keyEnd) };
}

function cryptRequest(packet, serverPrivate) {
  const { salt, nonce, ephemeralKey, ciphertext } = decodePacket(packet);
  const shared = diffieHellman({ privateKey: serverPrivate, publicKey: ephemeralKey });
  const prk = createHmac("sha256", salt).update(shared).digest();
  const info = Buffer.concat([Buffer.from("SetRetailX/license/v1", "utf8"), Buffer.from([1])]);
  const key = createHmac("sha256", prk).update(info).digest();
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAuthTag(ciphertext.subarray(-16));
  const plain = Buffer.concat([decipher.update(ciphertext.subarray(0, -16)), decipher.final()]);
  return { salt, key, request: JSON.parse(plain.toString("utf8")) };
}

function encryptResponse(salt, key, response) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  const ciphertext = Buffer.concat([cipher.update(response), cipher.final(), cipher.getAuthTag()]);
  return Buffer.concat([Buffer.from([1]), salt, nonce, ciphertext]);
}

async function readLicenses() {
  try { return JSON.parse(await readFile(licensesPath, "utf8")); }
  catch (error) { if (error.code === "ENOENT") return {}; throw error; }
}

async function writeLicenses(licenses) {
  await mkdir(dirname(licensesPath), { recursive: true });
  const temporary = `${licensesPath}.tmp`;
  await writeFile(temporary, `${JSON.stringify(licenses, null, 2)}\n`, { mode: 0o600 });
  await rename(temporary, licensesPath);
}

function invalid(status = 403) { return new Response("License rejected", { status }); }

const server = Bun.serve({ hostname: host, port, async fetch(req) {
  const url = new URL(req.url);
  if (req.method === "GET" && url.pathname === "/health") return new Response("ok");
  if (req.method !== "POST" || url.pathname !== "/v1/lease") return new Response("Not found", { status: 404 });
  const contentType = req.headers.get("content-type") || "";
  if (!contentType.startsWith("application/octet-stream")) return invalid(415);
  const bytes = Buffer.from(await req.arrayBuffer());
  if (bytes.length > maxPacketSize) return invalid(413);
  try {
    const serverPrivate = createPrivateKey(await readFile(encryptionPrivatePath));
    const signingPrivate = createPrivateKey(await readFile(signingPrivatePath));
    const { salt, key, request } = cryptRequest(bytes, serverPrivate);
    const { licenseKey, challenge, devicePublicKey, deviceId, proof } = request;
    if (typeof licenseKey !== "string" || licenseKey.length < 8 || licenseKey.length > 256
        || typeof challenge !== "string" || challenge.length < 32 || challenge.length > 128
        || typeof devicePublicKey !== "string" || typeof deviceId !== "string" || deviceId.length < 32
        || deviceId.length > 128 || typeof proof !== "string") return invalid();
    const deviceDer = Buffer.from(devicePublicKey, "base64");
    if (deviceDer.length > 1024) return invalid();
    const deviceKey = createPublicKey({ key: deviceDer, format: "der", type: "spki" });
    const proofMessage = Buffer.from(`${licenseKey}\n${challenge}\n${devicePublicKey}\n${deviceId}`, "utf8");
    if (!verify(null, proofMessage, deviceKey, Buffer.from(proof, "base64"))) return invalid();

    const licenses = await readLicenses();
    const record = licenses[licenseKey];
    if (!record || record.status !== "active") return invalid();
    if (record.devicePublicKey && record.devicePublicKey !== devicePublicKey) return invalid();
    if (record.deviceId && record.deviceId !== deviceId) return invalid();
    if (!record.devicePublicKey) {
      record.devicePublicKey = devicePublicKey;
      record.deviceId = deviceId;
      record.activatedAt = new Date().toISOString();
      await writeLicenses(licenses);
    }
    const expiresAt = Math.floor(Date.now() / 1000) + leaseSeconds;
    const payload = Buffer.from(JSON.stringify({ status: "active", challenge, devicePublicKey, deviceId, expiresAt }), "utf8");
    const responseSignature = sign(null, payload, signingPrivate);
    const framed = Buffer.alloc(4 + payload.length + 2 + responseSignature.length);
    framed.writeUInt32BE(payload.length, 0);
    payload.copy(framed, 4);
    framed.writeUInt16BE(responseSignature.length, 4 + payload.length);
    responseSignature.copy(framed, 6 + payload.length);
    return binary(encryptResponse(salt, key, framed));
  } catch (error) {
    console.error("License request rejected:", error);
    return new Response("License service temporarily unavailable", { status: 500,
      headers: { "cache-control": "no-store", "x-content-type-options": "nosniff" } });
  }
} });

console.log(`Local license server listening at http://${host}:${server.port}`);
