const VERSION = 1;
const MAX_PACKET_SIZE = 64 * 1024;
const LEASE_SECONDS = 4 * 60 * 60;
const encoder = new TextEncoder();

function response(body, status = 200, contentType = "text/plain; charset=utf-8") {
  return new Response(body, { status, headers: {
    "content-type": contentType,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  } });
}

function rejected(status = 403) { return response("License rejected", status); }

function pemBytes(pem) {
  const base64 = pem.replace(/-----BEGIN [^-]+-----|-----END [^-]+-----|\s/g, "");
  const binary = atob(base64);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function decodePacket(packet) {
  if (packet.length < 1 + 16 + 12 + 2 + 32 + 16 || packet[0] !== VERSION) throw new Error("bad packet");
  const salt = packet.subarray(1, 17);
  const nonce = packet.subarray(17, 29);
  const keyLength = (packet[29] << 8) | packet[30];
  const keyEnd = 31 + keyLength;
  if (keyLength < 32 || keyLength > 512 || keyEnd + 16 > packet.length) throw new Error("bad key length");
  return { salt, nonce, ephemeralDer: packet.subarray(31, keyEnd), ciphertext: packet.subarray(keyEnd) };
}

async function deriveSessionKey(ephemeralDer, salt, serverPrivatePem) {
  const clientKey = await crypto.subtle.importKey("spki", ephemeralDer,
    { name: "X25519" }, false, []);
  const serverKey = await crypto.subtle.importKey("pkcs8", pemBytes(serverPrivatePem),
    { name: "X25519" }, false, ["deriveBits"]);
  const shared = await crypto.subtle.deriveBits({ name: "X25519", public: clientKey }, serverKey, 256);
  const hkdfKey = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({
    name: "HKDF", hash: "SHA-256", salt,
    info: encoder.encode("SetRetailX/license/v1"),
  }, hkdfKey, 256));
}

async function aesGcm(mode, keyBytes, nonce, data) {
  const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, [mode]);
  return new Uint8Array(await crypto.subtle[mode](
    { name: "AES-GCM", iv: nonce, tagLength: 128 }, key, data));
}

function concatenate(...parts) {
  const result = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) { result.set(part, offset); offset += part.length; }
  return result;
}

function frameLease(payload, signature) {
  const framed = new Uint8Array(4 + payload.length + 2 + signature.length);
  new DataView(framed.buffer).setUint32(0, payload.length);
  framed.set(payload, 4);
  new DataView(framed.buffer).setUint16(4 + payload.length, signature.length);
  framed.set(signature, 6 + payload.length);
  return framed;
}

function validRequest(request) {
  return typeof request.licenseKey === "string" && request.licenseKey.length >= 8 && request.licenseKey.length <= 256
    && typeof request.challenge === "string" && /^[A-Za-z0-9_-]{43}$/.test(request.challenge)
    && typeof request.devicePublicKey === "string" && request.devicePublicKey.length <= 2048
    && typeof request.deviceId === "string" && /^[A-Za-z0-9_-]{43}$/.test(request.deviceId)
    && typeof request.proof === "string" && request.proof.length <= 256;
}

async function verifyDeviceProof(request) {
  const deviceDer = Uint8Array.from(atob(request.devicePublicKey), (char) => char.charCodeAt(0));
  if (deviceDer.length < 32 || deviceDer.length > 1024) return false;
  const publicKey = await crypto.subtle.importKey("spki", deviceDer,
    { name: "Ed25519" }, false, ["verify"]);
  const proof = Uint8Array.from(atob(request.proof), (char) => char.charCodeAt(0));
  const message = encoder.encode(`${request.licenseKey}\n${request.challenge}\n${request.devicePublicKey}\n${request.deviceId}`);
  return crypto.subtle.verify("Ed25519", publicKey, proof, message);
}

async function signLease(payload, privatePem) {
  const privateKey = await crypto.subtle.importKey("pkcs8", pemBytes(privatePem),
    { name: "Ed25519" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("Ed25519", privateKey, payload));
}

async function readBoundedBody(request) {
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > MAX_PACKET_SIZE) {
      await reader.cancel();
      throw new RangeError("packet too large");
    }
    chunks.push(value);
  }
  return concatenate(...chunks);
}

async function validateLicense(request, db) {
  let license = await db.prepare(
    "SELECT status, device_public_key, device_id FROM licenses WHERE license_key = ?1",
  ).bind(request.licenseKey).first();
  if (!license || license.status !== "active") return false;
  if (license.device_public_key && license.device_public_key !== request.devicePublicKey) return false;
  if (license.device_id && license.device_id !== request.deviceId) return false;
  if (!license.device_public_key) {
    await db.prepare(`UPDATE licenses
      SET device_public_key = ?1, device_id = ?2, activated_at = ?3
      WHERE license_key = ?4 AND status = 'active' AND device_public_key IS NULL`)
      .bind(request.devicePublicKey, request.deviceId, new Date().toISOString(), request.licenseKey).run();
    // Re-read after the conditional write so simultaneous first activations cannot bind two devices.
    license = await db.prepare(
      "SELECT status, device_public_key, device_id FROM licenses WHERE license_key = ?1",
    ).bind(request.licenseKey).first();
  }
  return license?.status === "active"
    && license.device_public_key === request.devicePublicKey
    && license.device_id === request.deviceId;
}

async function handleLease(request, env) {
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.startsWith("application/octet-stream")) return rejected(415);
  const length = Number(request.headers.get("content-length") || 0);
  if (length > MAX_PACKET_SIZE) return rejected(413);
  let packet;
  try { packet = await readBoundedBody(request); }
  catch (error) {
    if (error instanceof RangeError) return rejected(413);
    throw error;
  }

  try {
    const { salt, nonce, ephemeralDer, ciphertext } = decodePacket(packet);
    const key = await deriveSessionKey(ephemeralDer, salt, env.LICENSE_ENCRYPTION_PRIVATE_KEY);
    const plaintext = await aesGcm("decrypt", key, nonce, ciphertext);
    const licenseRequest = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
    if (!validRequest(licenseRequest) || !await verifyDeviceProof(licenseRequest)) return rejected();
    if (!await validateLicense(licenseRequest, env.LICENSES)) return rejected();

    const expiresAt = Math.floor(Date.now() / 1000) + LEASE_SECONDS;
    const payload = encoder.encode(JSON.stringify({ status: "active", challenge: licenseRequest.challenge,
      devicePublicKey: licenseRequest.devicePublicKey, deviceId: licenseRequest.deviceId, expiresAt }));
    const signature = await signLease(payload, env.LICENSE_SIGNING_PRIVATE_KEY);
    const nonceOut = crypto.getRandomValues(new Uint8Array(12));
    const responseCiphertext = await aesGcm("encrypt", key, nonceOut, frameLease(payload, signature));
    return new Response(concatenate(new Uint8Array([VERSION]), salt, nonceOut, responseCiphertext), {
      status: 200, headers: { "content-type": "application/octet-stream", "cache-control": "no-store",
        "x-content-type-options": "nosniff" },
    });
  } catch (error) {
    console.error("License request rejected:", error);
    return response("License service temporarily unavailable", 500);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return response("ok");
    if (request.method !== "POST" || url.pathname !== "/v1/lease") return response("Not found", 404);
    return handleLease(request, env);
  },
};
