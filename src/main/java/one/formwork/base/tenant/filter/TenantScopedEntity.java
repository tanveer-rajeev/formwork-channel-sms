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
        // Stub: real base class likely pulls tenantId from a
        // request-scoped/ThreadLocal tenant context automatically.
        // Left abstract-ish on purpose — subclasses/tests must set it.
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}

