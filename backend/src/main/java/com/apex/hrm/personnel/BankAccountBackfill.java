package com.apex.hrm.personnel;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Fail-closed V59 backfill. Must complete before Spring Boot is considered
 * started. Rows are locked, encrypted, and plaintext cleared atomically.
 * Deployment MUST stop old application writers before migrating.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BankAccountBackfill implements ApplicationRunner {
    private record Row(UUID id, UUID tenantId, UUID employeeId, String legacy, String ciphertext) {}
    private final JdbcTemplate jdbc;
    private final BankAccountCipher cipher;
    private final TransactionTemplate transaction;

    public BankAccountBackfill(JdbcTemplate jdbc, BankAccountCipher cipher, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(tx -> {
            // Prevent old writers/parallel migrations while changing sensitive data.
            jdbc.execute("LOCK TABLE employee_bank_account IN SHARE ROW EXCLUSIVE MODE");
            List<Row> rows = jdbc.query("""
                SELECT id,tenant_id,employee_id,account_no_legacy,account_no_ciphertext
                  FROM employee_bank_account
                 WHERE account_no_legacy IS NOT NULL OR account_no_ciphertext IS NULL
                    OR account_no_ciphertext NOT LIKE ?
                 FOR UPDATE
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getString(4), rs.getString(5)), "b1:" + cipher.currentKeyId() + ":%");
            for (Row row : rows) {
                String clear;
                if (row.ciphertext() != null) {
                    clear = cipher.decrypt(row.ciphertext(), row.tenantId(), row.employeeId());
                    if (row.legacy() != null && !row.legacy().equals(clear)) {
                        throw new IllegalStateException("Legacy and encrypted bank values differ; manual reconciliation required");
                    }
                } else {
                    if (row.legacy() == null || row.legacy().isBlank()) {
                        throw new IllegalStateException("Bank account missing both plaintext and encrypted value");
                    }
                    clear = row.legacy();
                }
                String encrypted = cipher.encrypt(clear, row.tenantId(), row.employeeId());
                jdbc.update("""
                    UPDATE employee_bank_account
                       SET account_no_ciphertext=?,account_no_legacy=NULL,updated_at=now()
                     WHERE id=? AND tenant_id=?
                    """, encrypted, row.id(), row.tenantId());
            }
            Integer remaining = jdbc.queryForObject("""
                SELECT count(*) FROM employee_bank_account
                WHERE account_no_legacy IS NOT NULL OR account_no_ciphertext IS NULL
                """, Integer.class);
            if (remaining == null || remaining != 0) {
                throw new IllegalStateException("Plaintext bank-account backfill incomplete");
            }
        });
    }

}
