package one.formwork.channel.sms.cost;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SmsCostEntityOnCreateTest {

    @Test
    void onCreate_NoSentAt_GeneratesSentAt() throws Exception {
        SmsCostEntity e = new SmsCostEntity();
        e.setTenantId(UUID.randomUUID()); // required by the fail-fast check

        var method = SmsCostEntity.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(e);

        assertNotNull(e.getSentAt());
        // id not asserted: it's @GeneratedValue, only populated by Hibernate
        // at real persist()/flush() time, not via a manually-invoked
        // @PrePersist callback. Covered separately by a @DataJpaTest.
    }

    @Test
    void onCreate_IdAlreadySet_KeepsId() throws Exception {
        SmsCostEntity e = new SmsCostEntity();
        e.setTenantId(UUID.randomUUID());
        UUID existingId = UUID.randomUUID();
        e.setId(existingId);
        var method = SmsCostEntity.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(e);
        assertEquals(existingId, e.getId());
    }

    @Test
    void onCreate_SentAtAlreadySet_KeepsSentAt() throws Exception {
        SmsCostEntity e = new SmsCostEntity();
        e.setTenantId(UUID.randomUUID());
        Instant existing = Instant.parse("2025-01-01T00:00:00Z");
        e.setSentAt(existing);
        var method = SmsCostEntity.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(e);
        assertEquals(existing, e.getSentAt());
    }

    @Test
    void onCreate_NoTenantId_ThrowsIllegalState() throws Exception {
        SmsCostEntity e = new SmsCostEntity();

        var method = SmsCostEntity.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);

        var ex = assertThrows(InvocationTargetException.class, () -> method.invoke(e));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}