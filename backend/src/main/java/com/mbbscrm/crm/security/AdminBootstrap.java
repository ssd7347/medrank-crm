package com.mbbscrm.crm.security;

import com.mbbscrm.crm.common.Phones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * Creates the first super admin from ADMIN_EMAIL / ADMIN_PASSWORD when the user table is empty.
 * After that, staff accounts are managed from the Users screen and these variables are ignored.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        String phone = b == null || isBlank(b.adminPhone()) ? null : Phones.normalize(b.adminPhone());
        if (users.count() > 0) {
            // Sign-in is by mobile number: give the first admin theirs if they do not have one yet.
            if (phone != null && !isBlank(b.adminEmail()) && users.findByPhone(phone).isEmpty()) {
                users.findByEmailIgnoreCase(b.adminEmail().trim()).filter(u -> isBlank(u.getPhone())).ifPresent(u -> {
                    u.setPhone(phone);
                    log.info("Set the mobile number of {} from ADMIN_PHONE", u.getEmail());
                });
            }
            return;
        }
        if (b == null || isBlank(b.adminEmail()) || isBlank(b.adminPassword())) {
            log.warn("No users exist. Set ADMIN_EMAIL and ADMIN_PASSWORD to create the first super admin.");
            return;
        }
        if (b.adminPassword().length() < 10) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 10 characters");
        }
        AppUser admin = new AppUser();
        admin.setFullName(isBlank(b.adminName()) ? "Super Admin" : b.adminName());
        admin.setEmail(b.adminEmail().trim().toLowerCase());
        admin.setPasswordHash(passwordEncoder.encode(b.adminPassword()));
        admin.setRole(Role.SUPER_ADMIN);
        admin.setPhone(phone);
        users.save(admin);
        log.info("Created first super admin {}", admin.getEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
