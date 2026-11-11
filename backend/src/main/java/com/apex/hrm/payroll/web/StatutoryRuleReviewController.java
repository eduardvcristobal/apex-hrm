package com.apex.hrm.payroll.web;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only validation of the global statutory rule tables and this tenant's wage-order coverage. */
@RestController
@RequestMapping("/api/reference/rule-review")
public class StatutoryRuleReviewController {
    private static final ZoneId HRM_ZONE=ZoneId.of("Asia/Manila");
    private static final List<String> AGENCIES=List.of("SSS","PHILHEALTH","PAGIBIG");
    private static final List<String> PERIODS=List.of("MONTHLY","SEMI_MONTHLY");
    private static final List<String> DAY_TYPES=List.of("ORDINARY","REST_DAY","SPECIAL_HOLIDAY","SPECIAL_HOLIDAY_REST_DAY","REGULAR_HOLIDAY","REGULAR_HOLIDAY_REST_DAY");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public StatutoryRuleReviewController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){this.jdbc=jdbc;this.tenants=tenants;}

    public record Issue(String severity,String category,String code,String message){}
    public record Review(LocalDate evaluatedAt,int issueCount,List<Issue> issues){}

    @GetMapping
    public Review review(@AuthenticationPrincipal HrmUserPrincipal user){
        LocalDate today=LocalDate.now(HRM_ZONE);
        List<Issue> issues=new ArrayList<>();
        for(String agency:AGENCIES){
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT source_reference FROM statutory_contribution_rule
                WHERE agency=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY effective_from DESC
                """,agency,today,today);
            if(rows.isEmpty()) add(issues,"BLOCKER","CONTRIBUTION","MISSING_CURRENT_RULE","No current contribution rule is configured for "+agency+".");
            else if(blank(rows.get(0).get("source_reference"))) add(issues,"WARNING","CONTRIBUTION","MISSING_SOURCE","Current "+agency+" rule has no official source reference.");
        }
        for(String period:PERIODS){
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT effective_from,lower_bound,upper_bound,source_reference FROM withholding_tax_bracket
                WHERE period_type=? AND effective_from=(SELECT max(effective_from) FROM withholding_tax_bracket WHERE period_type=? AND effective_from<=?)
                ORDER BY lower_bound
                """,period,period,today);
            if(rows.isEmpty()){
                add(issues,"BLOCKER","WITHHOLDING_TAX","MISSING_CURRENT_TABLE","No current "+period.replace('_',' ').toLowerCase()+" withholding table is configured.");
                continue;
            }
            if(blank(rows.get(0).get("source_reference"))) add(issues,"WARNING","WITHHOLDING_TAX","MISSING_SOURCE","Current "+period.replace('_',' ').toLowerCase()+" tax table has no official source reference.");
            BigDecimal expected=BigDecimal.ZERO;
            for(int i=0;i<rows.size();i++){
                Map<String,Object> row=rows.get(i);
                BigDecimal lower=decimal(row.get("lower_bound"));
                if(lower.compareTo(expected)!=0) add(issues,"BLOCKER","WITHHOLDING_TAX","BAND_GAP_OR_OVERLAP",period+" tax bands have a gap or overlap at "+lower+"; expected "+expected+".");
                Object upperValue=row.get("upper_bound");
                if(upperValue==null){
                    if(i<rows.size()-1) add(issues,"BLOCKER","WITHHOLDING_TAX","BANDS_AFTER_OPEN_ENDED",period+" tax table has bands after its open-ended band.");
                    expected=null;
                    break;
                }
                expected=decimal(upperValue);
            }
            if(expected!=null) add(issues,"BLOCKER","WITHHOLDING_TAX","OPEN_ENDED_BAND_MISSING",period+" tax table does not end with an open-ended band.");
        }
        for(String dayType:DAY_TYPES){
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT source_reference FROM premium_rule WHERE day_type=? AND effective_from<=? ORDER BY effective_from DESC LIMIT 1
                """,dayType,today);
            if(rows.isEmpty()) add(issues,"BLOCKER","PREMIUM","MISSING_CURRENT_RULE","No effective premium rule exists for "+dayType.replace('_',' ')+".");
            else if(blank(rows.get(0).get("source_reference"))) add(issues,"WARNING","PREMIUM","MISSING_SOURCE","Current "+dayType.replace('_',' ')+" premium rule has no official source reference.");
        }
        List<Map<String,Object>> overlaps=jdbc.queryForList("""
            SELECT current.agency,current.effective_from,prior.effective_from AS prior_from
            FROM statutory_contribution_rule current
            JOIN statutory_contribution_rule prior ON prior.agency=current.agency AND prior.id<>current.id
              AND prior.effective_from<current.effective_from
              AND (prior.effective_to IS NULL OR prior.effective_to>=current.effective_from)
            ORDER BY current.agency,current.effective_from
            """);
        for(Map<String,Object> row:overlaps) add(issues,"WARNING","CONTRIBUTION","OVERLAPPING_EFFECTIVE_DATES",
                row.get("agency")+" contribution rules overlap at "+row.get("effective_from")+" (prior rule from "+row.get("prior_from")+"); payroll selects the latest effective row.");

        List<Map<String,Object>> locations=tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
                "SELECT DISTINCT region_code,wage_sector FROM work_location WHERE tenant_id=? ORDER BY region_code,wage_sector",user.tenantId()));
        for(Map<String,Object> location:locations){
            String region=String.valueOf(location.get("region_code"));
            String sector=String.valueOf(location.get("wage_sector"));
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT reference FROM wage_order WHERE region_code=? AND sector=? AND effective_from<=?
                  AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC LIMIT 1
                """,region,sector,today,today);
            if(rows.isEmpty()) add(issues,"BLOCKER","WAGE_ORDER","MISSING_LOCATION_COVERAGE","No current wage order is configured for "+region+" / "+sector+" used by this tenant.");
            else if(blank(rows.get(0).get("reference"))) add(issues,"WARNING","WAGE_ORDER","MISSING_SOURCE","Current "+region+" / "+sector+" wage order has no order reference.");
        }
        issues.sort(Comparator.comparing(Issue::severity).thenComparing(Issue::category).thenComparing(Issue::code));
        return new Review(today,issues.size(),issues);
    }

    private static void add(List<Issue> issues,String severity,String category,String code,String message){issues.add(new Issue(severity,category,code,message));}
    private static boolean blank(Object value){return value==null||String.valueOf(value).isBlank();}
    private static BigDecimal decimal(Object value){return value instanceof BigDecimal n?n:new BigDecimal(String.valueOf(value));}
}
