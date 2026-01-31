package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferencesEntity, UUID> {
    Optional<UserPreferencesEntity> findByUserIdAndNamespace(UUID userId, String namespace);
}
