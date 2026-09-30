package com.mbbscrm.crm.portal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.security.AppProperties;

/**
 * Rotating refresh tokens for portal logins, kept apart from staff sessions so a portal token can never be
 * exchanged for a staff one. Reusing an already-rotated token revokes every session of that login.
 */
@Service
public class PortalSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalRefreshTokenRepository tokens;
    private final PortalAccountRepository accounts;
    private final AppProperties props;

    public PortalSessionService(PortalRefreshTokenRepository tokens, PortalAccountRepository accounts,
                                AppProperties props) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.props = props;
    }

    public record Rotation(PortalAccount account, String newToken) {
    }

    @Transactional
    public String create(Long accountId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new PortalRefreshToken(accountId, hash(token), Instant.now().plus(props.jwt().refreshTtl())));
        return token;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String token) {
        PortalRefreshToken existing = tokens.findByTokenHash(hash(token)).orElseThrow(PortalSessionService::invalid);
        if (existing.isRevoked()) {
            tokens.revokeAllForAccount(existing.getAccountId());
            throw invalid();
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            existing.revoke();
            throw invalid();
        }
        PortalAccount account = accounts.findById(existing.getAccountId())
                .filter(PortalAccount::isActive).orElseThrow(PortalSessionService::invalid);
        existing.revoke();
        return new Rotation(account, create(account.getId()));
    }

    @Transactional
    public void revoke(String token) {
        tokens.findByTokenHash(hash(token)).ifPresent(PortalRefreshToken::revoke);
    }

    @Transactional
    public void revokeAll(Long accountId) {
        tokens.revokeAllForAccount(accountId);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
