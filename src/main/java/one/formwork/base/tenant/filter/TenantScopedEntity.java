package one.formwork.base.tenant.filter;

import jakarta.persistence.*;

import java.util.UUID;

@MappedSuperclass
public abstract class TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @PrePersist
    protected void onCreate() {
        if (tenantId == null) {
            throw new IllegalStateException(
                    "tenantId must be set before persisting a " + getClass().getSimpleName()
            );
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}

