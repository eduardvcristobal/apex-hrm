package com.apex.hrm.payroll.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * M3.2.5 real PostgreSQL schema gate. Runs only when an isolated test database is supplied.
 * Never point HRM_TEST_DB_URL at a live employee database.
 */
@EnabledIfEnvironmentVariable(named = "HRM_TEST_DB_URL", matches = ".+")
class PostgreSqlPayrollSchemaCertificationTest {
    @Test
    void flywayCreatesDtrPayrollAndPaymentTablesWithTenantColumns() throws Exception {
        String url = System.getenv("HRM_TEST_DB_URL");
        String user = System.getenv().getOrDefault("HRM_TEST_DB_USER", "hrm");
        String password = System.getenv().getOrDefault("HRM_TEST_DB_PASSWORD", "hrm");
        assertTrue(url.startsWith("jdbc:postgresql:"), "Only PostgreSQL is accepted for certification");
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        flyway.validate();
        String[] tables = {"employee", "employee_schedule", "time_log", "dtr_day",
                "pay_period", "payroll_run", "payroll_employee", "payroll_line",
                "payslip", "bank_file"};
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String table : tables) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'tenant_id'")) {
                    statement.setString(1, table);
                    try (ResultSet rs = statement.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1), "Missing tenant_id on " + table);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM pg_constraint WHERE conname = " +
                    "'uq_dtr_day_tenant_id_employee_id_work_date'")) {
                try (ResultSet rs = statement.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "DTR uniqueness constraint absent");
                }
            }
        }
    }
}
