package com.apex.hrm.payroll.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mandatory, atomic V60 backfill and key-rotation audit before accepting traffic. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class BankExportBackfill implements ApplicationRunner {
    private record Row(UUID id, UUID tenant, UUID run, String filename,
                       String legacy, String encrypted, String hash, Instant purgedAt) {}

    private final JdbcTemplate jdbc;
    private final BankExportCipher cipher;
    private final TransactionTemplate transaction;

    public BankExportBackfill(JdbcTemplate jdbc, BankExportCipher cipher, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(status -> {
            jdbc.execute("LOCK TABLE bank_file IN SHARE ROW EXCLUSIVE MODE");
            List<Row> rows = jdbc.query("""
                SELECT id,tenant_id,run_id,file_name,content_legacy,content_ciphertext,
                       content_sha256,purged_at FROM bank_file FOR UPDATE
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()));
            if (!rows.isEmpty() && rows.stream().anyMatch(r -> r.purgedAt() == null) && !cipher.hasCurrentKey())
                throw new IllegalStateException("HRM_BANK_EXPORT_KEY is required for existing unpurged bank files");
            for (Row row : rows) {
                if (row.purgedAt() != null) {
                    if (row.legacy() != null || row.encrypted() != null)
                        throw new IllegalStateException("Purged bank file unexpectedly contains payload");
                    continue;
                }
                String clear;
                if (row.encrypted() != null) {
                    clear = cipher.decrypt(row.encrypted(), row.tenant(), row.run(), row.filename());
                    if (row.legacy() != null && !row.legacy().equals(clear))
                        throw new IllegalStateException("Bank export legacy content does not match encrypted content");
                } else if (row.legacy() != null) {
                    clear = row.legacy();
                } else {
                    throw new IllegalStateException("Bank export lacks a valid payload");
                }
                String hash = BankExportCipher.sha256(clear);
                if (row.hash() != null && !row.hash().equals(hash))
                    throw new IllegalStateException("Bank export SHA-256 integrity mismatch");
                if (row.legacy() != null || cipher.needsRotation(row.encrypted()) || row.hash() == null) {
                    String encrypted = cipher.encrypt(clear, row.tenant(), row.run(), row.filename());
                    jdbc.update("""
                        UPDATE bank_file SET content_ciphertext=?, content_legacy=NULL,
                             content_sha256=?, updated_at=now()
                        WHERE id=? AND tenant_id=?
                        """, encrypted, hash, row.id(), row.tenant());
                }
            }
            Integer remaining = jdbc.queryForObject("""
                SELECT count(*) FROM bank_file
                WHERE content_legacy IS NOT NULL OR (purged_at IS NULL AND content_ciphertext IS NULL)
                   OR (purged_at IS NOT NULL AND content_ciphertext IS NOT NULL)
                """, Integer.class);
            if (remaining == null || remaining != 0)
                throw new IllegalStateException("Bank file encryption backfill incomplete");
        });
    }
}
