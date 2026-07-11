package one.formwork.base.tenant.flyway;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

public class FlywayModuleSupport {
    // Stub: guessing at intent — namespaces migrations under a per-module
    // schema/table prefix so multiple modules can share one DataSource
    // without Flyway history collisions. Real behavior may differ.
    public static Flyway create(DataSource dataSource, String moduleTag) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/" + moduleTag)
                .table("flyway_schema_history_" + moduleTag)
                .load();
    }
}
