package org.example.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class SqliteTestDatabase implements AutoCloseable {
    private final java.sql.Connection keeper;
    public final JdbcTemplate jdbc;

    public SqliteTestDatabase() throws java.sql.SQLException {
        var ds = new DriverManagerDataSource("jdbc:sqlite:file:test_" + java.util.UUID.randomUUID()
                + "?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        keeper = ds.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
    }

    public void user(long id, String role) {
        jdbc.update("INSERT INTO users(id,email,username,nickname,role,status,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
                id, "u" + id + "@example.test", "user" + id, "user" + id, role,
                "2026-11-01T00:00:00", "2026-11-01T00:00:00");
    }

    public static void inject(Object target, String field, Object value) throws ReflectiveOperationException {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Override public void close() throws java.sql.SQLException { keeper.close(); }
}
