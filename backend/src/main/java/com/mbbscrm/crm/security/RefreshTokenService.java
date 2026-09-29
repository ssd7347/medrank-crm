package com.mbbscrm.crm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import org.springframework.http.HttpStatus;

/**
 * Opaque, rotating refresh tokens. Each use returns a new token and revokes the old one; presenting an
 * already-revoked token is treated as theft and revokes every session for that user.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AppUserRepository users;
    private final AppProperties props;

    public RefreshTokenService(RefreshTokenRepository repository, AppUserRepository users, AppProperties props) {
        this.repository = repository;
        this.users = users;
        this.props = props;
    }

    public record Rotation(AppUser user, String newToken) {
    }

    @Transactional
    public String create(AppUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(user.getId(), hash(token), Instant.now().plus(props.jwt().refreshTtl())));
        return token;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String token) {
        RefreshToken existing = repository.findByTokenHash(hash(token))
                .orElseThrow(RefreshTokenService::invalid);
        if (existing.isRevoked()) {
            repository.revokeAllForUser(existing.getUserId());
            throw invalid();
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            existing.revoke();
            throw invalid();
        }
        AppUser user = users.findById(existing.getUserId()).filter(AppUser::isActive)
                .orElseThrow(RefreshTokenService::invalid);
        existing.revoke();
        return new Rotation(user, create(user));
    }

    @Transactional
    public void revoke(String token) {
        repository.findByTokenHash(hash(token)).ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllForUser(userId);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
