package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** LAN discovery and authenticated, encrypted peer-to-peer additive synchronization. */
public final class PeerMeshService implements AutoCloseable {
    public static final int PORT = 45873;
    private static final int MAX_LINE = 256 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path stateDir = Path.of(System.getProperty("user.home"), ".setretailx", "peer-mesh");
    private final PeerSyncRepository repository = new PeerSyncRepository();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "peer-mesh"); thread.setDaemon(true); return thread;
    });
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, String> trustedKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Consumer<Peer> changed;
    private final TrustPrompt trustPrompt;
    private final KeyPair identity;
    private final String nodeId;
    private volatile String peerName;
    private volatile boolean closed;
    private ServerSocket server;
    private DatagramSocket discoverySocket;

    public PeerMeshService(Consumer<Peer> changed, TrustPrompt trustPrompt) {
        this.changed = changed == null ? peer -> { } : changed;
        this.trustPrompt = trustPrompt;
        try {
            Files.createDirectories(stateDir);
            identity = loadOrCreateIdentity();
            nodeId = digest(identity.getPublic().getEncoded()).substring(0, 24);
            peerName = loadName();
            loadTrustedPeers();
            startNetwork();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось запустить локальную peer-сеть: " + error.getMessage(), error);
        }
    }

    public String peerName() { return peerName; }
    public String nodeId() { return nodeId; }
    public String fingerprint() {
        try { return fingerprint(identity.getPublic()); }
        catch (Exception error) { throw new IllegalStateException("Не удалось вычислить fingerprint ключа.", error); }
    }
    public int port() { return PORT; }
    public Map<String, Peer> peers() { return Map.copyOf(peers); }

    public synchronized void setPeerName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Введите имя этого пира.");
        peerName = name.trim();
        Files.writeString(stateDir.resolve("peer-name.txt"), peerName, StandardCharsets.UTF_8);
        announce();
    }

    public CompletableFuture<SyncResult> synchronize(Peer peer) {
        return CompletableFuture.supplyAsync(() -> {
            try { return synchronizeNow(peer); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, workers);
    }

    public boolean isTrusted(String id) { return trustedKeys.containsKey(id); }

    private void startNetwork() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0", PORT));
        discoverySocket = new DatagramSocket(null);
        discoverySocket.setReuseAddress(true);
        discoverySocket.bind(new InetSocketAddress(PORT));
        workers.execute(this::acceptLoop);
        workers.execute(this::discoveryLoop);
        workers.scheduleWithFixedDelay(this::announce, 1, 4, TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(this::expirePeers, 5, 5, TimeUnit.SECONDS);
    }

    private void announce() {
        if (closed) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            ObjectNode message = JSON.createObjectNode();
            message.put("type", "setretailx-peer-v1"); message.put("id", nodeId);
            message.put("name", peerName); message.put("port", PORT);
            byte[] bytes = JSON.writeValueAsBytes(message);
            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("255.255.255.255"), PORT));
        } catch (Exception ignored) { }
    }

    private void discoveryLoop() {
        byte[] buffer = new byte[2048];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(packet);
                JsonNode message = JSON.readTree(packet.getData(), packet.getOffset(), packet.getLength());
                if (!"setretailx-peer-v1".equals(message.path("type").asText())) continue;
                String id = message.path("id").asText();
                if (id.isBlank() || id.equals(nodeId)) continue;
                lastSeen.put(id, System.currentTimeMillis());
                Peer peer = new Peer(id, message.path("name").asText("Офисный пир"),
                        packet.getAddress().getHostAddress(), message.path("port").asInt(PORT),
                        trustedKeys.containsKey(id),
                        true, peers.getOrDefault(id, new Peer(id, "", "", PORT, false, false, "")).status());
                peers.put(id, peer);
                changed.accept(peer);
            } catch (SocketException closedSocket) { if (!closed) markNetworkError(closedSocket); }
            catch (Exception ignored) { }
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept(); socket.setSoTimeout(30_000);
                workers.execute(() -> handleIncoming(socket));
            } catch (SocketException error) { if (!closed) markNetworkError(error); }
            catch (IOException ignored) { }
        }
    }

    private void handleIncoming(Socket socket) {
        try (socket; BufferedReader input = reader(socket); BufferedWriter output = writer(socket)) {
            String helloLine = readLine(input);
            JsonNode hello = JSON.readTree(helloLine);
            if (!"hello".equals(hello.path("type").asText()) || !verifyHello(hello)) return;
            String id = hello.path("id").asText();
            if (id.isBlank() || id.equals(nodeId)) return;
            PublicKey peerKey = decodePublic("Ed25519", hello.path("identityKey").asText());
            String fingerprint = fingerprint(peerKey);
            if (!acceptKey(id, hello.path("name").asText(), socket.getInetAddress().getHostAddress(), fingerprint)) return;

            KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            String nonce = randomToken(24);
            ObjectNode response = JSON.createObjectNode();
            response.put("type", "hello"); response.put("id", nodeId); response.put("name", peerName);
            response.put("identityKey", b64(identity.getPublic().getEncoded()));
            response.put("ephemeralKey", b64(ephemeral.getPublic().getEncoded())); response.put("nonce", nonce);
            response.put("signature", sign(identity.getPrivate(), (helloLine + canonical(unsigned(response))).getBytes(StandardCharsets.UTF_8)));
            String responseLine = JSON.writeValueAsString(response);
            writeLine(output, responseLine);
            String ackLine = readLine(input);
            JsonNode ack = JSON.readTree(ackLine);
            if (!"ready".equals(ack.path("type").asText()) || !verifySignature(peerKey,
                    (helloLine + responseLine).getBytes(StandardCharsets.UTF_8), ack.path("signature").asText())) return;
            byte[] key = sharedKey(ephemeral.getPrivate(), decodePublic("X25519", hello.path("ephemeralKey").asText()),
                    helloLine + canonical(unsigned(response)));
            JsonNode incoming = decrypt(readLine(input), key);
            PeerSyncRepository.MergeSummary merged = repository.importSnapshot(incoming.path("snapshot"));
            ObjectNode result = JSON.createObjectNode();
            result.set("snapshot", repository.exportSnapshot());
            ObjectNode counts = result.putObject("counts"); counts.put("products", merged.products()); counts.put("workspaceRows", merged.workspaceRows());
            writeLine(output, encrypt(result, key));
            touchPeer(id, hello.path("name").asText(), socket.getInetAddress().getHostAddress(), true, "Подключен");
        } catch (Exception error) {
            try { touchPeerFromSocket(socket, false, error.getMessage()); } catch (Exception ignored) { }
        }
    }

    private SyncResult synchronizeNow(Peer peer) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.address(), peer.port()), 5000);
            socket.setSoTimeout(60_000);
            try (BufferedReader input = reader(socket); BufferedWriter output = writer(socket)) {
                KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
                ObjectNode hello = JSON.createObjectNode();
                hello.put("type", "hello"); hello.put("id", nodeId); hello.put("name", peerName);
                hello.put("identityKey", b64(identity.getPublic().getEncoded()));
                hello.put("ephemeralKey", b64(ephemeral.getPublic().getEncoded())); hello.put("nonce", randomToken(24));
                hello.put("signature", sign(identity.getPrivate(), canonical(unsigned(hello)).getBytes(StandardCharsets.UTF_8)));
                String helloLine = JSON.writeValueAsString(hello); writeLine(output, helloLine);
                String responseLine = readLine(input); JsonNode response = JSON.readTree(responseLine);
                if (!"hello".equals(response.path("type").asText())) throw new IOException("Пир прислал некорректное рукопожатие.");
                if (!peer.id().equals(response.path("id").asText()))
                    throw new IOException("Ответ пришёл от другого пира, чем выбранный в списке.");
                PublicKey peerKey = decodePublic("Ed25519", response.path("identityKey").asText());
                if (!digest(peerKey.getEncoded()).startsWith(response.path("id").asText()))
                    throw new IOException("ID пира не соответствует его публичному ключу.");
                String fingerprint = fingerprint(peerKey);
                ObjectNode signedResponse = (ObjectNode) response.deepCopy(); signedResponse.remove("signature");
                if (!verifySignature(peerKey, (helloLine + canonical(signedResponse)).getBytes(StandardCharsets.UTF_8),
                        response.path("signature").asText())) throw new IOException("Подпись пира не прошла проверку.");
                if (!acceptKey(response.path("id").asText(), response.path("name").asText(), peer.address(), fingerprint))
                    throw new IOException("Новый пир не подтверждён пользователем.");
                ObjectNode ack = JSON.createObjectNode(); ack.put("type", "ready");
                ack.put("signature", sign(identity.getPrivate(), (helloLine + responseLine).getBytes(StandardCharsets.UTF_8)));
                writeLine(output, JSON.writeValueAsString(ack));
                byte[] key = sharedKey(ephemeral.getPrivate(), decodePublic("X25519", response.path("ephemeralKey").asText()),
                        helloLine + canonical(unsigned(response)));
                ObjectNode request = JSON.createObjectNode(); request.set("snapshot", repository.exportSnapshot());
                writeLine(output, encrypt(request, key));
                JsonNode result = decrypt(readLine(input), key);
                PeerSyncRepository.MergeSummary summary = repository.importSnapshot(result.path("snapshot"));
                long addedProducts = summary.products() + result.path("counts").path("products").asLong();
                long addedWorkspace = summary.workspaceRows() + result.path("counts").path("workspaceRows").asLong();
                touchPeer(response.path("id").asText(), response.path("name").asText(), peer.address(), true, "Синхронизировано");
                return new SyncResult(addedProducts, addedWorkspace);
            }
        }
    }

    private boolean acceptKey(String id, String name, String address, String fingerprint) throws Exception {
        String previous = trustedKeys.get(id);
        if (previous != null) {
            if (!MessageDigest.isEqual(previous.getBytes(StandardCharsets.UTF_8), fingerprint.getBytes(StandardCharsets.UTF_8))) {
                Peer changedKey = new Peer(id, name, address, PORT, false, true,
                        "Ключ изменился — требуется повторное подтверждение");
                peers.put(id, changedKey); changed.accept(changedKey);
                return false;
            }
            return true;
        }
        if (trustPrompt == null || !trustPrompt.confirm(new Peer(id, name, address, PORT, false, true, fingerprint), fingerprint)) return false;
        trustedKeys.put(id, fingerprint);
        saveTrustedPeers();
        return true;
    }

    private boolean verifyHello(JsonNode hello) throws Exception {
        PublicKey key = decodePublic("Ed25519", hello.path("identityKey").asText());
        if (!digest(key.getEncoded()).startsWith(hello.path("id").asText())) return false;
        ObjectNode unsigned = (ObjectNode) hello.deepCopy(); unsigned.remove("signature");
        return verifySignature(key, canonical(unsigned).getBytes(StandardCharsets.UTF_8), hello.path("signature").asText());
    }

    private void touchPeer(String id, String name, String address, boolean trusted, String status) {
        lastSeen.put(id, System.currentTimeMillis());
        Peer peer = new Peer(id, name, address, PORT, trusted || trustedKeys.containsKey(id), true, status);
        peers.put(id, peer); changed.accept(peer);
    }

    private void expirePeers() {
        long cutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(14);
        lastSeen.forEach((id, seen) -> {
            if (seen >= cutoff) return;
            Peer current = peers.get(id);
            if (current != null && current.online()) {
                Peer offline = new Peer(current.id(), current.name(), current.address(), current.port(),
                        current.trusted(), false, "Не в сети");
                peers.put(id, offline); changed.accept(offline);
            }
        });
    }

    private void touchPeerFromSocket(Socket socket, boolean trusted, String status) {
        String address = socket.getInetAddress().getHostAddress();
        peers.values().stream().filter(peer -> peer.address().equals(address)).findFirst()
                .ifPresent(peer -> {
                    Peer failed = new Peer(peer.id(), peer.name(), address, peer.port(),
                            trusted || peer.trusted(), true, status == null ? "Ошибка обмена" : status);
                    peers.put(peer.id(), failed); changed.accept(failed);
                });
    }

    private void markNetworkError(Exception error) { }

    private KeyPair loadOrCreateIdentity() throws Exception {
        Path privatePath = stateDir.resolve("identity-private.der"), publicPath = stateDir.resolve("identity-public.der");
        if (Files.isRegularFile(privatePath) && Files.isRegularFile(publicPath)) {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath))));
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try {
            Files.createFile(privatePath, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException error) { Files.createFile(privatePath); }
        Files.write(privatePath, pair.getPrivate().getEncoded()); Files.write(publicPath, pair.getPublic().getEncoded());
        return pair;
    }

    private String loadName() throws IOException {
        Path path = stateDir.resolve("peer-name.txt");
        if (Files.isRegularFile(path)) { String name = Files.readString(path).trim(); if (!name.isBlank()) return name; }
        String host = InetAddress.getLocalHost().getHostName();
        return host == null || host.isBlank() ? "Офисный пир" : host;
    }

    private void loadTrustedPeers() throws IOException {
        Path path = stateDir.resolve("trusted-peers.json"); if (!Files.isRegularFile(path)) return;
        JsonNode json = JSON.readTree(path.toFile()); json.fields().forEachRemaining(entry -> trustedKeys.put(entry.getKey(), entry.getValue().asText()));
    }

    private void saveTrustedPeers() throws IOException { JSON.writeValue(stateDir.resolve("trusted-peers.json").toFile(), trustedKeys); }

    private static byte[] sharedKey(PrivateKey privateKey, PublicKey publicKey, String transcript) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519"); agreement.init(privateKey); agreement.doPhase(publicKey, true);
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(agreement.generateSecret());
        digest.update(transcript.getBytes(StandardCharsets.UTF_8)); return digest.digest();
    }

    private static String encrypt(JsonNode payload, byte[] key) throws Exception {
        byte[] nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        byte[] serialized = JSON.writeValueAsBytes(payload);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(serialized); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(compressed.toByteArray());
        ObjectNode packet = JSON.createObjectNode(); packet.put("nonce", b64(nonce)); packet.put("data", b64(encrypted));
        packet.put("compressed", true);
        return JSON.writeValueAsString(packet);
    }

    private static JsonNode decrypt(String line, byte[] key) throws Exception {
        JsonNode packet = JSON.readTree(line); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(packet.path("nonce").asText())));
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(packet.path("data").asText()));
        if (!packet.path("compressed").asBoolean()) return JSON.readTree(decrypted);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(decrypted))) {
            return JSON.readTree(gzip);
        }
    }

    private static String sign(PrivateKey key, byte[] content) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(content); return b64(signature.sign());
    }

    private static boolean verifySignature(PublicKey key, byte[] content, String signed) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initVerify(key); signature.update(content);
        return signature.verify(Base64.getDecoder().decode(signed));
    }

    private static String canonical(JsonNode node) throws Exception { return JSON.writeValueAsString(node); }
    private static ObjectNode unsigned(JsonNode node) {
        ObjectNode unsigned = (ObjectNode) node.deepCopy();
        unsigned.remove("signature");
        return unsigned;
    }
    private static PublicKey decodePublic(String algorithm, String encoded) throws Exception {
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    private static String fingerprint(PublicKey key) throws Exception { return digest(key.getEncoded()).toUpperCase().replaceAll("(.{4})", "$1:").replaceAll(":$", ""); }
    private static String digest(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static String randomToken(int bytes) { byte[] token = new byte[bytes]; new java.security.SecureRandom().nextBytes(token); return b64(token); }
    private static BufferedReader reader(Socket socket) throws IOException { return new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); }
    private static BufferedWriter writer(Socket socket) throws IOException { return new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)); }
    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(); int c;
        while ((c = reader.read()) != -1 && c != '\n') { if (line.length() >= MAX_LINE) throw new IOException("Сообщение peer-сети превышает лимит."); if (c != '\r') line.append((char)c); }
        if (c == -1 && line.isEmpty()) throw new IOException("Пир закрыл соединение."); return line.toString();
    }
    private static void writeLine(BufferedWriter writer, String value) throws IOException { writer.write(value); writer.write('\n'); writer.flush(); }

    @Override public void close() {
        closed = true;
        if (discoverySocket != null) discoverySocket.close();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    public record Peer(String id, String name, String address, int port, boolean trusted, boolean online, String status) { }
    public record SyncResult(long productsAdded, long workspaceRowsAdded) { }
    @FunctionalInterface public interface TrustPrompt { boolean confirm(Peer peer, String fingerprint) throws Exception; }
}
