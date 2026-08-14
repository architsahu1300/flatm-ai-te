package com.flatmaite.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, java.util.UUID> {
  java.util.Optional<User> findByEmailIgnoreCase(String email);

  java.util.Optional<User> findByPhone(String phone);

  boolean existsByEmailIgnoreCase(String email);
}
