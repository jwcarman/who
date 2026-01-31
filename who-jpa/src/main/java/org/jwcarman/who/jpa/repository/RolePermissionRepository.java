package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, UUID> {
    List<RolePermissionEntity> findByRoleId(UUID roleId);

    @Query("SELECT rp.permission FROM RolePermissionEntity rp WHERE rp.roleId IN :roleIds")
    List<String> findPermissionsByRoleIds(@Param("roleIds") List<UUID> roleIds);

    void deleteByRoleId(UUID roleId);
}
