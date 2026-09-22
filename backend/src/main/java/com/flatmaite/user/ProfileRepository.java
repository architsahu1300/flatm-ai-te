package com.flatmaite.user;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProfileRepository extends JpaRepository<Profile, java.util.UUID> {
  java.util.Optional<Profile> findByUserId(java.util.UUID userId);

  /**
   * The viewer's profile locality id and nothing else — the one column the city scope is derived
   * from on every search. A projection rather than the entity: {@code spring.jpa.open-in-view} is
   * false, so anything resolved outside the transaction that loaded it fails, and a scalar has
   * nothing left to resolve.
   *
   * <p>Empty list = no profile row at all. A single null element = a profile whose locality was
   * never set. Both are "no city" (spec §4.11), and callers treat them identically — an
   * {@code Optional} could not tell them apart anyway, because a null scalar is not a present value.
   */
  @Query("select p.currentLocalityId from Profile p where p.userId = ?1")
  List<UUID> findCurrentLocalityId(UUID userId);
}
