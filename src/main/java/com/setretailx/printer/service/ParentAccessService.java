package com.setretailx.printer.service;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.AccountRepository;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Parent-side authentication and permission checks shared by local and remote API calls. */
public final class ParentAccessService {
    private static final long SESSION_LIFETIME_MILLIS = Duration.ofHours(12).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccountRepository accounts;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ParentAccessService(AccountRepository accounts) {
        this.accounts = java.util.Objects.requireNonNull(accounts);
    }

    public LoginResult login(String username, char[] password) throws SQLException {
        try {
            requireParent();
            if (password == null) throw new IllegalArgumentException("Введите пароль.");
            AccountRepository.Account account = accounts.authenticate(username, password);
            if (account == null) return null;
            byte[] rawToken = new byte[32];
            RANDOM.nextBytes(rawToken);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
            java.util.Arrays.fill(rawToken, (byte) 0);
            long expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MILLIS;
            sessions.put(token, new Session(account, expiresAt));
            return new LoginResult(token, account, expiresAt);
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    public AccountRepository.Account require(String token, AccountRepository.Permission permission)
            throws SQLException {
        requireParent();
        if (token == null || token.isBlank()) throw new AccessDeniedException("Требуется вход в систему.");
        Session session = sessions.get(token);
        if (session == null) throw new AccessDeniedException("Сеанс завершён. Войдите повторно.");
        if (session.expiresAt() <= System.currentTimeMillis()) {
            sessions.remove(token, session);
            throw new AccessDeniedException("Сеанс завершён. Войдите повторно.");
        }
        AccountRepository.Account current = accounts.findEnabled(session.account().id());
        if (current == null) {
            sessions.remove(token, session);
            throw new AccessDeniedException("Учётная запись отключена. Войдите повторно.");
        }
        if (!current.can(permission)) throw new AccessDeniedException("Недостаточно прав для этой операции.");
        return current;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public void logoutAll() { sessions.clear(); }

    private static void requireParent() {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT)
            throw new AccessDeniedException("API родительского узла недоступно на дочернем узле.");
    }

    private record Session(AccountRepository.Account account, long expiresAt) { }

    public record LoginResult(String token, AccountRepository.Account account, long expiresAt) { }

    public static final class AccessDeniedException extends SecurityException {
        public AccessDeniedException(String message) { super(message); }
    }
}
