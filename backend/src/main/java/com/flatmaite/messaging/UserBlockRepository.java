package com.flatmaite.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlock.Key> {
}
