package com.apex.hrm.payroll.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.payroll.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3.2.9: real Postgres-backed per-employee bank reject -> recovery -> reconcile.
 * Uses only synthetic identities and a disposable CI database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${HRM_TEST_DB_URL:jdbc:postgresql://localhost:5432/apex_hrm_test}",
        "spring.datasource.username=${HRM_TEST_DB_USER:hrm}",
        "spring.datasource.password=${HRM_TEST_DB_PASSWORD:hrm}"
})
@EnabledIfEnvironmentVariable(named = "HRM_TEST_DB_URL", matches = ".+")
class PostgreSqlPartialBankRecoveryE2ETest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BankFileService files;
    @Autowired BankCreditResultService imported;
    @Autowired PayrollSettlementService settlements;
    @Autowired PayrollService payroll;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void rejectedEmployeeRecoveredWithoutReexportingAcceptedEmployee() {
        UUID tenant=UUID.randomUUID(), company=UUID.randomUUID(), group=UUID.randomUUID();
        UUID period=UUID.randomUUID(), run=UUID.randomUUID(), template=UUID.randomUUID();
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,code,name,slug,display_name) VALUES (?,?,?,?,?)",
                tenant,"PBR-"+tenant.toString().substring(0,8),"Partial bank recovery",
                "pbr-"+tenant,"Partial bank recovery");
        jdbc.update("INSERT INTO company(id,tenant_id,legal_name,company_code) VALUES (?,?,?,?)",
                company,tenant,"Synthetic Bank Recovery Co","PBR");
        jdbc.update("INSERT INTO pay_group(id,tenant_id,company_id,code,name) VALUES (?,?,?,?,?)",
                group,tenant,company,"PBR","Bank recovery group");
        jdbc.update("INSERT INTO pay_period(id,tenant_id,pay_group_id,start_date,end_date,pay_date) VALUES (?,?,?,?,?,?)",
                period,tenant,group,java.sql.Date.valueOf(LocalDate.of(2026,10,1)),
                java.sql.Date.valueOf(LocalDate.of(2026,10,15)),
                java.sql.Date.valueOf(LocalDate.of(2026,10,20)));
        jdbc.update("INSERT INTO payroll_run(id,tenant_id,pay_period_id,pay_group_id,status,total_net,employee_count) " +
                "VALUES (?,?,?,?,?,?,?)",run,tenant,period,group,"LOCKED",new BigDecimal("300.00"),2);
        jdbc.update("INSERT INTO bank_template(id,tenant_id,bank_code,name,column_spec) VALUES (?,?,?,?,?)",
                template,tenant,"PBRBANK","Test bank","EMPLOYEE_NO,ACCOUNT_NO,AMOUNT");
        seedEmployee(tenant,company,run,first,"PBR-100","100.00","000111");
        seedEmployee(tenant,company,run,second,"PBR-200","200.00","000222");

        TenantContext.set(tenant);
        var original=files.generate(run,template).file();
        assertEquals(2,original.getRecordCount());
        UUID recorder=UUID.randomUUID(),confirmer=UUID.randomUUID();
        var response=imported.importResults(original.getId(),
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\\n" +
                "PBR-100,100.00,ACCEPTED,ACCEPT-1\\n" +
                "PBR-200,200.00,REJECTED,REJECT-2",
                "bank-statement-original",recorder);
        assertEquals(1,response.accepted());
        assertEquals(1,response.rejected());
        assertEquals(0,new BigDecimal("100.00").compareTo(imported.acceptedAmount(original.getId())));
        assertThrows(BusinessException.class,()->settlements.record(run,original.getId(),
                new BigDecimal("300.00"),"BANK-FULL","full-is-invalid",recorder));
        var partial=settlements.record(run,original.getId(),new BigDecimal("100.00"),
                "BANK-FIRST-"+original.getId(),"bank-statement-original",recorder);
        settlements.confirm(partial.getId(),confirmer);
        assertEquals("PARTIAL",settlements.summary(run).status());
        assertThrows(BusinessException.class,()->payroll.markPaid(run));

        var recovery=files.recoverRejected(original.getId()).file();
        assertEquals(original.getId(),recovery.getRecoverySourceFileId());
        assertEquals(1,recovery.getRecordCount());
        assertEquals(0,new BigDecimal("200.00").compareTo(recovery.getTotalAmount()));
        assertTrue(recovery.getContent().contains("PBR-200,000222,200.00"));
        assertFalse(recovery.getContent().contains("PBR-100"));
        assertThrows(BusinessException.class,()->files.recoverRejected(original.getId()));
        assertThrows(BusinessException.class,()->imported.importResults(original.getId(),
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\\nPBR-100,100.00,ACCEPTED,X", "duplicate",recorder));

        var recoveryResponse=imported.importResults(recovery.getId(),
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\\nPBR-200,200.00,ACCEPTED,RECOVERED",
                "bank-statement-recovery",recorder);
        assertEquals(1,recoveryResponse.accepted());
        var finalAck=settlements.record(run,recovery.getId(),new BigDecimal("200.00"),
                "BANK-RECOVERY-"+recovery.getId(),"bank-statement-recovery",recorder);
        settlements.confirm(finalAck.getId(),confirmer);
        assertEquals("RECONCILED",settlements.summary(run).status());
        assertEquals("PAID",payroll.markPaid(run).getStatus());
    }

    private void seedEmployee(UUID tenant,UUID company,UUID run,UUID id,String number,
                              String net,String accountNo) {
        jdbc.update("INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date) " +
                "VALUES (?,?,?,?,?,?,?)",id,tenant,company,number,"Synthetic","Employee",
                java.sql.Date.valueOf(LocalDate.of(2025,1,1)));
        jdbc.update("INSERT INTO payroll_employee(tenant_id,run_id,employee_id,gross,net_pay) VALUES (?,?,?,?,?)",
                tenant,run,id,new BigDecimal(net),new BigDecimal(net));
        jdbc.update("INSERT INTO employee_bank_account(tenant_id,employee_id,bank_code,account_no) VALUES (?,?,?,?)",
                tenant,id,"PBRBANK",accountNo);
    }
}
