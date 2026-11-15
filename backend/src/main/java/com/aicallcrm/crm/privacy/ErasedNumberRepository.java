package com.aicallcrm.crm.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedNumberRepository extends JpaRepository<ErasedNumber, Long> {

    Optional<ErasedNumber> findByPhoneHash(String phoneHash);

    default Optional<ErasedNumber> forPhone(String phone) {
        return phone == null || phone.isBlank() ? Optional.empty() : findByPhoneHash(hash(phone));
    }

    static String hash(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
