package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentityEntity, UUID> {
    Optional<ExternalIdentityEntity> findByIssuerAndSubject(String issuer, String subject);

    List<ExternalIdentityEntity> findByUserId(UUID userId);

    long countByUserId(UUID userId);
}
