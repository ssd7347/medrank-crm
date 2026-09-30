package com.mbbscrm.crm.security;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.mbbscrm.crm.user.AppUser;

/** Issues short-lived access tokens. The subject is the user id; the role travels as a claim. */
@Service
public class TokenService {

    public static final String ROLE_CLAIM = "role";
    public static final String NAME_CLAIM = "name";
    public static final String BRANCH_CLAIM = "branch";
    /** Role claim of family portal tokens. Deliberately not a member of {@link com.mbbscrm.crm.common.Role}. */
    public static final String PORTAL_ROLE = "PORTAL";
    private static final String ISSUER = "mbbs-crm";

    private final JwtEncoder encoder;
    private final AppProperties props;

    public TokenService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(props.jwt().accessTtl()))
                .subject(String.valueOf(user.getId()))
                .claim(ROLE_CLAIM, user.getRole().name())
                .claim(NAME_CLAIM, user.getFullName());
        if (user.getBranch() != null) {
            claims.claim(BRANCH_CLAIM, user.getBranch().getId());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /** Access token for a student/parent portal login. It carries no staff role, only {@code PORTAL}. */
    public String issuePortalToken(Long accountId, String displayName) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(props.jwt().accessTtl()))
                .subject(String.valueOf(accountId))
                .claim(ROLE_CLAIM, PORTAL_ROLE)
                .claim(NAME_CLAIM, displayName)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTtlSeconds() {
        return props.jwt().accessTtl().toSeconds();
    }
}
