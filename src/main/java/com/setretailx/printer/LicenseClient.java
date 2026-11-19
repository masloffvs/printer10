package com.setretailx.printer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

/** Performs the signed, encrypted license lease handshake before opening application data. */
final class LicenseClient {
    private static final int VERSION = 1;
    private static final long MAX_LEASE_SECONDS = 4 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path LICENSE_DIR = Path.of(System.getProperty("user.home"), ".setretailx", "license");
    private static final Path DEVICE_PRIVATE = LICENSE_DIR.resolve("device-ed25519.pk8");
    private static final Path DEVICE_PUBLIC = LICENSE_DIR.resolve("device-ed25519.pub");
    private static final Path CACHED_LEASE = LICENSE_DIR.resolve("lease.json");
    private static final Path CLOCK_FILE = LICENSE_DIR.resolve("last-clock.txt");

    private LicenseClient() { }

    static void check() throws LicenseException {
        check(true);
    }

    static void checkForRenewal() throws LicenseException {
        check(false);
    }

    private static void check(boolean forceOnline) throws LicenseException {
        String url = setting("SETRETAILX_LICENSE_URL");
        String encryptionKeyPath = setting("SETRETAILX_LICENSE_ENCRYPTION_KEY_FILE");
        String verifyKeyPath = setting("SETRETAILX_LICENSE_VERIFY_KEY_FILE");
        String licenseKey = setting("SETRETAILX_LICENSE_KEY");
        if (url.isBlank() || encryptionKeyPath.isBlank() || verifyKeyPath.isBlank() || licenseKey.isBlank()) {
            throw new LicenseException("Настройте SETRETAILX_LICENSE_URL, "
                    + "SETRETAILX_LICENSE_ENCRYPTION_KEY_FILE, SETRETAILX_LICENSE_VERIFY_KEY_FILE и "
                    + "SETRETAILX_LICENSE_KEY в .env.");
        }
        try {
            Files.createDirectories(LICENSE_DIR);
            restrictDirectory(LICENSE_DIR);
            KeyPair device = loadOrCreateDeviceKey();
            PublicKey encryptionKey = readPublicKey(Path.of(encryptionKeyPath), "X25519");
            PublicKey verifyKey = readPublicKey(Path.of(verifyKeyPath), "Ed25519");
            long now = Instant.now().getEpochSecond();
            checkClock(now);
            if (!forceOnline) {
                Lease cached = readCachedLease(verifyKey, device.getPublic());
                if (cached != null && cached.allowed() && cached.expiresAt() - now > 30 * 60) return;
            }
            try {
                String deviceId = machineFingerprint();
                Lease lease = requestLease(URI.create(url), licenseKey, deviceId, device, encryptionKey, verifyKey);
                saveLease(lease);
                saveClock(now);
                if (!lease.allowed()) throw new LicenseException("Лицензия отклонена сервером.");
                return;
            } catch (LicenseException error) {
                throw error;
            } catch (Exception networkError) {
                Lease cached = readCachedLease(verifyKey, device.getPublic());
                if (cached != null && cached.allowed() && cached.expiresAt() > now
                        && cached.expiresAt() - now <= MAX_LEASE_SECONDS) {
                    saveClock(now);
                    return;
                }
                throw new LicenseException("Сервер лицензирования недоступен, а действующий льготный "
                        + "период исчерпан. Проверьте соединение или обратитесь для продления.", networkError);
            }
        } catch (LicenseException error) {
            throw error;
        } catch (Exception error) {
            throw new LicenseException("Не удалось проверить лицензию: " + safeMessage(error), error);
        }
    }

    private static Lease requestLease(URI endpoint, String licenseKey, String deviceId, KeyPair device,
                                      PublicKey serverEncryptionKey, PublicKey serverVerifyKey) throws Exception {
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme()))
            throw new LicenseException("Адрес сервера лицензирования должен использовать HTTP или HTTPS.");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null)
            throw new LicenseException("Некорректный адрес сервера лицензирования.");
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        String challengeText = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
        String publicKeyText = Base64.getEncoder().encodeToString(device.getPublic().getEncoded());
        String proofText = licenseKey + "\n" + challengeText + "\n" + publicKeyText + "\n" + deviceId;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(device.getPrivate());
        signer.update(proofText.getBytes(StandardCharsets.UTF_8));
        String body = "{\"licenseKey\":\"" + jsonEscape(licenseKey) + "\",\"challenge\":\""
                + challengeText + "\",\"devicePublicKey\":\"" + publicKeyText + "\",\"deviceId\":\""
                + deviceId + "\",\"proof\":\""
                + Base64.getEncoder().encodeToString(signer.sign()) + "\"}";

        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = deriveSharedKey(ephemeral.getPrivate(), serverEncryptionKey, salt);
        byte[] requestNonce = new byte[12];
        RANDOM.nextBytes(requestNonce);
        byte[] encryptedRequest = encryptPacket(key, requestNonce, body.getBytes(StandardCharsets.UTF_8));
        byte[] packet = packRequest(salt, requestNonce, ephemeral.getPublic().getEncoded(), encryptedRequest);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/octet-stream")
                .header("Accept", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(packet)).build();
        HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400 && response.statusCode() < 500)
            throw new LicenseException("Сервер отклонил лицензию (HTTP " + response.statusCode() + ").");
        if (response.statusCode() != 200) throw new IOException("Сервер ответил HTTP " + response.statusCode());
        byte[] plainResponse = decryptResponse(response.body(), salt, key);
        return parseAndVerifyLease(plainResponse, serverVerifyKey, challengeText, deviceId, device.getPublic());
    }

    private static byte[] packRequest(byte[] salt, byte[] nonce, byte[] publicKey, byte[] ciphertext)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION); out.write(salt); out.write(nonce);
        out.writeShort(publicKey.length); out.write(publicKey); out.write(ciphertext);
        return bytes.toByteArray();
    }

    private static byte[] decryptResponse(byte[] packet, byte[] expectedSalt, byte[] key) throws Exception {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(packet));
        if (in.readUnsignedByte() != VERSION) throw new IOException("Неподдерживаемый формат ответа.");
        byte[] salt = in.readNBytes(16);
        if (!MessageDigest.isEqual(salt, expectedSalt)) throw new IOException("Некорректный ответ сервера.");
        byte[] nonce = in.readNBytes(12);
        byte[] encrypted = in.readAllBytes();
        if (nonce.length != 12 || encrypted.length < 16) throw new IOException("Обрезанный ответ сервера.");
        return crypt(Cipher.DECRYPT_MODE, key, nonce, encrypted);
    }

    private static byte[] deriveSharedKey(PrivateKey privateKey, PublicKey publicKey, byte[] salt) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey); agreement.doPhase(publicKey, true);
        byte[] shared = agreement.generateSecret();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(shared);
        byte[] info = "SetRetailX/license/v1".getBytes(StandardCharsets.UTF_8);
        byte[] expand = new byte[info.length + 1];
        System.arraycopy(info, 0, expand, 0, info.length);
        expand[expand.length - 1] = 1;
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = mac.doFinal(expand);
        java.util.Arrays.fill(shared, (byte) 0);
        return result;
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] bytes) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(bytes);
    }

    private static byte[] encryptPacket(byte[] key, byte[] nonce, byte[] bytes) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encryptedAndTag = cipher.doFinal(bytes);
        int encryptedLength = encryptedAndTag.length - 16;
        ByteArrayOutputStream packet = new ByteArrayOutputStream(encryptedAndTag.length);
        packet.write(encryptedAndTag, 0, encryptedLength);
        packet.write(encryptedAndTag, encryptedLength, 16);
        return packet.toByteArray();
    }

    private static Lease parseAndVerifyLease(byte[] bytes, PublicKey verifyKey, String challenge,
                                              String deviceId, PublicKey deviceKey) throws Exception {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        int payloadLength = in.readInt();
        if (payloadLength < 2 || payloadLength > 16_384) throw new IOException("Некорректная аренда лицензии.");
        byte[] payload = in.readNBytes(payloadLength);
        int signatureLength = in.readUnsignedShort();
        byte[] signature = in.readNBytes(signatureLength);
        if (payload.length != payloadLength || signature.length != signatureLength || in.available() != 0)
            throw new IOException("Обрезанная аренда лицензии.");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(verifyKey); verifier.update(payload);
        if (!verifier.verify(signature)) throw new IOException("Подпись лицензирующего сервера не прошла проверку.");
        String json = new String(payload, StandardCharsets.UTF_8);
        String responseChallenge = jsonString(json, "challenge");
        String deviceKeyText = jsonString(json, "devicePublicKey");
        String responseDeviceId = jsonString(json, "deviceId");
        String status = jsonString(json, "status");
        long expiresAt = jsonLong(json, "expiresAt");
        if (!challenge.equals(responseChallenge) || !deviceId.equals(responseDeviceId)
                || !MessageDigest.isEqual(deviceKey.getEncoded(), Base64.getDecoder().decode(deviceKeyText)))
            throw new IOException("Ответ лицензии не относится к этой установке.");
        if (expiresAt <= Instant.now().getEpochSecond()
                || expiresAt - Instant.now().getEpochSecond() > MAX_LEASE_SECONDS)
            throw new IOException("Сервер вернул аренду за пределами допустимого периода.");
        return new Lease("active".equals(status), expiresAt, payload, signature);
    }

    private static Lease readCachedLease(PublicKey verifyKey, PublicKey deviceKey) {
        try {
            if (!Files.isRegularFile(CACHED_LEASE)) return null;
            String json = Files.readString(CACHED_LEASE, StandardCharsets.UTF_8);
            byte[] payload = Base64.getDecoder().decode(jsonString(json, "payload"));
            byte[] signature = Base64.getDecoder().decode(jsonString(json, "signature"));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(verifyKey); verifier.update(payload);
            if (!verifier.verify(signature)) return null;
            String payloadText = new String(payload, StandardCharsets.UTF_8);
            String storedDevice = jsonString(payloadText, "devicePublicKey");
            String storedDeviceId = jsonString(payloadText, "deviceId");
            if (!MessageDigest.isEqual(deviceKey.getEncoded(), Base64.getDecoder().decode(storedDevice))) return null;
            if (!machineFingerprint().equals(storedDeviceId)) return null;
            long expiry = jsonLong(payloadText, "expiresAt");
            return new Lease("active".equals(jsonString(payloadText, "status")), expiry, payload, signature);
        } catch (Exception ignored) { return null; }
    }

    private static void saveLease(Lease lease) throws IOException {
        String value = "{\"payload\":\"" + Base64.getEncoder().encodeToString(lease.payload())
                + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(lease.signature()) + "\"}";
        writeAtomic(CACHED_LEASE, value);
    }

    private static KeyPair loadOrCreateDeviceKey() throws Exception {
        if (Files.isRegularFile(DEVICE_PRIVATE) && Files.isRegularFile(DEVICE_PUBLIC)) {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(DEVICE_PRIVATE)));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(DEVICE_PUBLIC)));
            return new KeyPair(publicKey, privateKey);
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.createDirectories(LICENSE_DIR);
        writeAtomic(DEVICE_PRIVATE, pair.getPrivate().getEncoded());
        restrictFile(DEVICE_PRIVATE);
        writeAtomic(DEVICE_PUBLIC, pair.getPublic().getEncoded());
        return pair;
    }

    private static PublicKey readPublicKey(Path path, String algorithm) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (text.startsWith("-----BEGIN")) {
            text = text.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            bytes = Base64.getDecoder().decode(text);
        }
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(bytes));
    }

    private static void checkClock(long now) throws Exception {
        if (!Files.isRegularFile(CLOCK_FILE)) return;
        long previous = Long.parseLong(Files.readString(CLOCK_FILE).trim());
        if (now + 300 < previous) throw new LicenseException("Обнаружен перевод системных часов назад. "
                + "Синхронизируйте часы и подключитесь к серверу лицензирования.");
    }

    private static String machineFingerprint() throws Exception {
        String configured = setting("SETRETAILX_LICENSE_DEVICE_ID");
        String machineId = configured;
        if (machineId.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                for (Path path : new Path[]{Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id")}) {
                    if (Files.isRegularFile(path)) {
                        machineId = Files.readString(path, StandardCharsets.US_ASCII).trim();
                        if (!machineId.isBlank()) break;
                    }
                }
            } else if (os.contains("win")) {
                machineId = commandOutput("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                        "/v", "MachineGuid").replaceFirst("(?is).*MachineGuid\\s+REG_SZ\\s+", "").trim();
            } else if (os.contains("mac") || os.contains("darwin")) {
                machineId = commandOutput("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                        .replaceAll("(?s).*\\\"IOPlatformUUID\\\"\\s*=\\s*\\\"([^\\\"]+)\\\".*", "$1").trim();
            }
        }
        if (machineId == null || machineId.isBlank())
            throw new IOException("Не удалось получить идентификатор компьютера; задайте "
                    + "SETRETAILX_LICENSE_DEVICE_ID в .env.");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((System.getProperty("os.name", "") + "\n" + machineId).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String commandOutput(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Получение идентификатора компьютера превысило время ожидания.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IOException("Не удалось получить идентификатор компьютера.");
        return output;
    }

    private static void saveClock(long now) throws IOException { writeAtomic(CLOCK_FILE, Long.toString(now)); }

    private static void writeAtomic(Path path, String value) throws IOException {
        writeAtomic(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomic(Path path, byte[] value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.write(temporary, value);
        restrictFile(temporary);
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(path);
    }

    private static void restrictDirectory(Path path) {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (Exception ignored) { }
    }
    private static void restrictFile(Path path) {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE)); }
        catch (Exception ignored) { }
    }

    private static String setting(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = System.getProperty(key, "");
        return value == null ? "" : value.trim();
    }

    private static String jsonString(String json, String key) throws IOException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"" + key
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!matcher.find()) throw new IOException("В ответе отсутствует поле " + key);
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    private static long jsonLong(String json, String key) throws IOException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"" + key
                + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) throw new IOException("В ответе отсутствует поле " + key);
        return Long.parseLong(matcher.group(1));
    }
    private static String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Lease(boolean allowed, long expiresAt, byte[] payload, byte[] signature) { }

    static final class LicenseException extends Exception {
        LicenseException(String message) { super(message); }
        LicenseException(String message, Throwable cause) { super(message, cause); }
    }
}
