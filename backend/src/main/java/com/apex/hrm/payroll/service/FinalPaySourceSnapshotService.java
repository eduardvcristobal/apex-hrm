package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auditable append-only source lineage and strict cutoff completeness preflight.
 * Snapshot captures raw source IDs, payroll periods, DTR, compensation and ledgers.
 * It does not compute a legally approved final payout or dispatch funds.
 */
@Service
public class FinalPaySourceSnapshotService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final ObjectMapper mapper;

    public FinalPaySourceSnapshotService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,ObjectMapper mapper) {
        this.jdbc=jdbc;this.tenants=tenants;this.mapper=mapper;
    }

    public record Snapshot(UUID id,UUID settlementId,UUID payrollEmployeeId,String sourceSha256,
        int cutoffGapDays,int compensationGapDays,int unfinalizedDtrDays,
        String completenessStatus,boolean payoutCertified,boolean disbursementEnabled,
        String capturedAt,JsonNode evidence) {}

    private record Scope(UUID settlementId,UUID employeeId,LocalDate from,LocalDate to) {}
    private record Captured(String json,JsonNode document,int cutoffGaps,int compensationGaps,
        int openDtr,String completenessStatus,String digest) {}

    private static final String SOURCE_SQL = """
        WITH params AS (
            SELECT ?::uuid AS tenant_id, ?::uuid AS employee_id,
                   ?::date AS start_date, ?::date AS end_date
        )
        SELECT jsonb_build_object(
          'schema','M3.1.18-v1',
          'periodStart',p.start_date,'periodEnd',p.end_date,
          'payrollPeriods',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'runId',r.id,'periodId',pr.id,'from',pr.start_date,'to',pr.end_date,
                'status',r.status) ORDER BY pr.start_date,pr.end_date,r.id),'[]'::jsonb)
            FROM payroll_employee pe JOIN payroll_run r
              ON r.tenant_id=pe.tenant_id AND r.id=pe.run_id
            JOIN pay_period pr ON pr.tenant_id=r.tenant_id AND pr.id=r.pay_period_id
            WHERE pe.tenant_id=p.tenant_id AND pe.employee_id=p.employee_id
              AND pr.end_date>=p.start_date AND pr.start_date<=p.end_date),
          'payrollLines',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',l.id,'runId',l.run_id,'code',l.item_code,
                'category',l.category,'amount',l.amount,'taxable',l.taxable,
                'sourceType',l.source_type,'sourceId',l.source_id,
                'periodFrom',pr.start_date,'periodTo',pr.end_date,'runStatus',r.status)
                ORDER BY pr.start_date,r.id,l.id),'[]'::jsonb)
            FROM payroll_line l JOIN payroll_run r
              ON r.tenant_id=l.tenant_id AND r.id=l.run_id
            JOIN pay_period pr ON pr.tenant_id=r.tenant_id AND pr.id=r.pay_period_id
            WHERE l.tenant_id=p.tenant_id AND l.employee_id=p.employee_id
              AND pr.end_date>=p.start_date AND pr.start_date<=p.end_date),
          'dtr',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',d.id,'date',d.work_date,'status',d.status,
                'absent',d.absent,'paidLeave',d.paid_leave,'regularMinutes',d.regular_minutes,
                'lateMinutes',d.late_minutes,'undertimeMinutes',d.undertime_minutes,
                'approvedOvertimeMinutes',d.ot_approved_minutes)
                ORDER BY d.work_date,d.id),'[]'::jsonb)
            FROM dtr_day d WHERE d.tenant_id=p.tenant_id AND d.employee_id=p.employee_id
              AND d.work_date BETWEEN p.start_date AND p.end_date),
          'compensation',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',c.id,'from',c.effective_from,'to',c.effective_to,
                'basis',c.pay_basis,'rate',c.base_rate) ORDER BY c.effective_from,c.id),'[]'::jsonb)
            FROM compensation c WHERE c.tenant_id=p.tenant_id AND c.employee_id=p.employee_id
              AND c.effective_from<=p.end_date AND
                  (c.effective_to IS NULL OR c.effective_to>=p.start_date)),
          'loans',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',l.id,'status',l.status,'balance',l.balance,'principal',l.principal)
                ORDER BY l.id),'[]'::jsonb)
            FROM loan l WHERE l.tenant_id=p.tenant_id AND l.employee_id=p.employee_id
              AND l.start_date<=p.end_date),
          'leaveEntries',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',l.id,'typeId',l.leave_type_id,'date',l.entry_date,'days',l.days,
                'entryType',l.entry_type,'convertible',t.convertible)
                ORDER BY l.entry_date,l.id),'[]'::jsonb)
            FROM leave_ledger l JOIN leave_type t
                ON t.tenant_id=l.tenant_id AND t.id=l.leave_type_id
            WHERE l.tenant_id=p.tenant_id AND l.employee_id=p.employee_id
                AND l.entry_date<=p.end_date),
          'annualAdjustments',(
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'id',a.id,'status',a.status,'amount',a.amount,'code',a.item_code)
                ORDER BY a.id),'[]'::jsonb)
            FROM adjustment a WHERE a.tenant_id=p.tenant_id AND a.employee_id=p.employee_id
                AND a.status IN ('PENDING','APPROVED')
          )
        )::text AS evidence
        FROM params p
        """;

    public Snapshot capture(HrmUserPrincipal user,UUID settlementId) {
        return tenants.inTenant(user.tenantId(),()->{
            Scope scope=scope(user.tenantId(),settlementId);
            Captured source=collect(user.tenantId(),scope);
            List<Snapshot> made=jdbc.query("""
                INSERT INTO payroll_final_pay_source_snapshot
                 (tenant_id,settlement_id,payroll_employee_id,source_sha256,evidence,
                  cutoff_gap_days,compensation_gap_days,unfinalized_dtr_days,
                  completeness_status,captured_by)
                VALUES(?,?,?,?,?::jsonb,?,?,?,?,?)
                ON CONFLICT (tenant_id,settlement_id,source_sha256) DO NOTHING
                RETURNING id,settlement_id,payroll_employee_id,source_sha256,evidence::text AS evidence,
                  cutoff_gap_days,compensation_gap_days,unfinalized_dtr_days,
                  completeness_status,captured_at
                """,(rs,i)->map(rs),
                user.tenantId(),scope.settlementId(),scope.employeeId(),source.digest(),
                source.json(),source.cutoffGaps(),source.compensationGaps(),source.openDtr(),
                source.completenessStatus(),user.id());
            if(made.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Unchanged source evidence already captured; create a new capture only after source change");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?, 'FINAL_PAY_SOURCE_SNAPSHOT_CAPTURED',
                       'payroll_final_pay_source_snapshot',?)
                """,user.tenantId(),user.id(),made.get(0).id());
            return made.get(0);
        });
    }

    public List<Snapshot> list(HrmUserPrincipal user,UUID settlementId){
        return tenants.inTenant(user.tenantId(),()->{
            // Reject guessed cross-tenant settlement IDs rather than return finance metadata.
            scope(user.tenantId(),settlementId);
            return jdbc.query("""
                SELECT id,settlement_id,payroll_employee_id,source_sha256,
                  evidence::text AS evidence,cutoff_gap_days,compensation_gap_days,
                  unfinalized_dtr_days,completeness_status,captured_at
                FROM payroll_final_pay_source_snapshot
                WHERE tenant_id=? AND settlement_id=?
                ORDER BY captured_at DESC,id DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),settlementId);
        });
    }

    private Scope scope(UUID tenant,UUID settlementId){
        List<Scope> scopes=jdbc.query("""
            SELECT s.id,l.payroll_employee_id,
                   GREATEST(date_trunc('year',c.effective_date)::date,e.hire_date) AS start_date,
                   c.effective_date AS end_date
            FROM payroll_final_pay_settlement s
            JOIN personnel_hr_case c ON c.tenant_id=s.tenant_id
               AND c.id=s.case_id AND c.employee_id=s.employee_id
            JOIN payroll_personnel_identity_link l ON l.tenant_id=s.tenant_id
               AND l.personnel_employee_id=s.employee_id AND l.status='APPROVED'
            JOIN employee e ON e.tenant_id=l.tenant_id AND e.id=l.payroll_employee_id
            WHERE s.tenant_id=? AND s.id=? AND s.status='APPROVED'
            FOR UPDATE OF s,l
            """,(rs,i)->new Scope(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class)),
            tenant,settlementId);
        if(scopes.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Approved settlement and verified payroll identity not found");
        Scope s=scopes.get(0);
        if(s.from().isAfter(s.to()))throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Separation precedes recorded employee hire");
        return s;
    }

    private Captured collect(UUID tenant,Scope scope){
        String json=jdbc.queryForObject(SOURCE_SQL,String.class,
            tenant,scope.employeeId(),scope.from(),scope.to());
        try{
            JsonNode doc=mapper.readTree(json);
            int cutoff=gapDays(doc.get("payrollPeriods"),"from","to",
                scope.from(),scope.to(),true);
            int comp=gapDays(doc.get("compensation"),"from","to",
                scope.from(),scope.to(),false);
            int open=0;
            for(JsonNode d:doc.path("dtr"))
                if(!isFinal(d.path("status").asText("")))open++;
            boolean noDtr=doc.path("dtr").isEmpty();
            String status=cutoff>0 || comp>0 || open>0 || noDtr
                ? "INCOMPLETE_SOURCE" : "REVIEW_REQUIRED";
            return new Captured(json,doc,cutoff,comp,open,status,sha256(json));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalStateException("Cannot decode canonical source snapshot",e);
        }
    }
    static boolean isFinal(String status){
        return "LOCKED".equals(status)||"FINALIZED".equals(status)||"APPROVED".equals(status);
    }

    /** Period completeness uses inclusive dates; overlapping rows do not double count. */
    static int gapDays(JsonNode rows,String startField,String endField,
        LocalDate from,LocalDate to,boolean onlyLocked){
        int missing=0;
        for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)){
            boolean covered=false;
            for(JsonNode row:rows){
                if(onlyLocked && !List.of("LOCKED","PAID").contains(row.path("status").asText("")))continue;
                JsonNode end=row.path(endField);
                LocalDate lo=LocalDate.parse(row.path(startField).asText());
                LocalDate hi=end.isNull() || end.isMissingNode()?to:LocalDate.parse(end.asText());
                if(!day.isBefore(lo)&&!day.isAfter(hi)){covered=true;break;}
            }
            if(!covered)missing++;
        }
        return missing;
    }

    static String sha256(String json){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Snapshot map(ResultSet rs) throws SQLException{
        String json=rs.getString("evidence");
        if(!sha256(json).equals(rs.getString("source_sha256")))
            throw new IllegalStateException("Stored financial source evidence hash mismatch");
        try{
            return new Snapshot(rs.getObject("id",UUID.class),rs.getObject("settlement_id",UUID.class),
                rs.getObject("payroll_employee_id",UUID.class),rs.getString("source_sha256"),
                rs.getInt("cutoff_gap_days"),rs.getInt("compensation_gap_days"),
                rs.getInt("unfinalized_dtr_days"),rs.getString("completeness_status"),
                false,false,rs.getString("captured_at"),mapper.readTree(json));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalStateException("Invalid stored financial evidence",e);
        }
    }
}
