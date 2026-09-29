package com.mbbscrm.crm.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mbbscrm.crm.common.Role;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByActiveTrueAndRoleInOrderByFullName(Collection<Role> roles);

    List<AppUser> findAllByOrderByFullName();
}
