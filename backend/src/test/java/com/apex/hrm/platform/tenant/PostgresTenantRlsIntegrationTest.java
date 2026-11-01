package com.apex.hrm.platform.tenant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real Flyway RLS policy using a non-owner database role.
 * Requires Docker; Testcontainers skips this class when Docker is unavailable.
 * This tests branch RLS, not yet all personnel/payroll tables.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTenantRlsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("hrm_security_test").withUsername("hrm_migration").withPassword("test-password");

    private static final String RESTRICTED_USER = "hrm_rls_probe";
    private static final String RESTRICTED_PASSWORD = "test-probe-password";
    private static final UUID FIRST_TENANT = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID SECOND_TENANT = UUID.fromString("00000000-0000-4000-8000-000000000202");

    @BeforeAll
    static void setup() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        try (Connection connection = migrationConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE " + RESTRICTED_USER + " LOGIN PASSWORD '" + RESTRICTED_PASSWORD + "'");
            sql.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_USER);
            sql.execute("GRANT SELECT, INSERT ON branch TO " + RESTRICTED_USER);
            for (UUID tenant : new UUID[]{FIRST_TENANT, SECOND_TENANT}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)")) {
                    statement.setObject(1, tenant);
                    statement.setString(2, "TENANT-" + tenant.toString().substring(32));
                    statement.setString(3, "Tenant " + tenant);
                    statement.executeUpdate();
                }
                UUID company = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO company (id, tenant_id, legal_name, company_code) VALUES (?, ?, ?, ?)")) {
                    statement.setObject(1, company);
                    statement.setObject(2, tenant);
                    statement.setString(3, "Company " + tenant);
                    statement.setString(4, "C-" + tenant.toString().substring(32));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO branch (tenant_id, company_id, branch_code, branch_name) VALUES (?, ?, ?, ?)")) {
                    statement.setObject(1, tenant);
                    statement.setObject(2, company);
                    statement.setString(3, "HQ");
                    statement.setString(4, "Head office");
                    statement.executeUpdate();
                }
            }
        }
    }

    private static Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection restrictedConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), RESTRICTED_USER, RESTRICTED_PASSWORD);
    }

    private static long countBranches(Connection c) throws SQLException {
        try (Statement query = c.createStatement(); ResultSet rs = query.executeQuery("SELECT COUNT(*) FROM branch")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void setLocalTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenant.toString());
            try (ResultSet result = set.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    @Test
    void noContextSeesNoTenantRowsAndContextCannotLeakAcrossTransactions() throws SQLException {
        try (Connection c = restrictedConnection()) {
            assertEquals(0, countBranches(c), "missing scope must deny all tenant rows");
            c.setAutoCommit(false);
            setLocalTenant(c, FIRST_TENANT);
            assertEquals(1, countBranches(c));
            c.commit();
            assertEquals(0, countBranches(c), "SET LOCAL must expire after commit");
            c.rollback();
        }
    }

    @Test
    void eachTenantSeesOnlyItsOwnRowsAndCrossTenantWritesFail() throws SQLException {
        try (Connection c = restrictedConnection()) {
            c.setAutoCommit(false);
            setLocalTenant(c, SECOND_TENANT);
            assertEquals(1, countBranches(c));
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO branch (tenant_id, company_id, branch_code, branch_name) "
                  + "SELECT ?, id, 'INJECT', 'cross tenant' FROM company WHERE tenant_id=? LIMIT 1")) {
                // The restricted account has no access to company: use a fixture's UUID instead.
                // Denied reads are established independently by the branch SELECT assertion.
            }
            c.rollback();
        }
        UUID foreignCompany;
        try (Connection admin = migrationConnection();
             PreparedStatement find = admin.prepareStatement("SELECT id FROM company WHERE tenant_id=?")) {
            find.setObject(1, FIRST_TENANT);
            try (ResultSet rs = find.executeQuery()) {
                assertTrue(rs.next());
                foreignCompany = rs.getObject(1, UUID.class);
            }
        }
        try (Connection c = restrictedConnection()) {
            c.setAutoCommit(false);
            setLocalTenant(c, SECOND_TENANT);
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO branch (tenant_id, company_id, branch_code, branch_name) VALUES (?, ?, 'INJECT', 'cross tenant')")) {
                insert.setObject(1, FIRST_TENANT);
                insert.setObject(2, foreignCompany);
                assertThrows(SQLException.class, insert::executeUpdate,
                    "RLS WITH CHECK must reject cross-tenant inserts");
            }
            c.rollback();
        }
    }
}
