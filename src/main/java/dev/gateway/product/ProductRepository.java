package dev.gateway.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Product}.
 *
 * <p>
 * <strong>No explicit tenant filter is needed here.</strong>
 * PostgreSQL Row-Level Security (RLS) silently filters all queries
 * based on the {@code app.current_tenant} session variable set by
 * {@link dev.gateway.config.HibernateMultiTenantConfig}.
 *
 * <p>
 * This is intentional by design: application-level filtering would create
 * a secondary enforcement point that could desync with the database policy,
 * while providing false assurance. The RLS policy is the authoritative guard.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    
    @Query(value = "SELECT current_setting('app.current_tenant')", nativeQuery = true)
    String getCurrentTenantSetting();
}
