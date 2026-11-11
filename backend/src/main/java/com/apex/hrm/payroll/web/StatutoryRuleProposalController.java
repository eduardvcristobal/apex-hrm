package com.apex.hrm.payroll.web;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.security.PlatformAdminPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant submissions are proposals only; shared reference rules change after platform review. */
@RestController
public class StatutoryRuleProposalController {
    private static final Set<String> TYPES=Set.of("CONTRIBUTION","TAX_BRACKET","PREMIUM","WAGE_ORDER");
    private static final Set<String> AGENCIES=Set.of("SSS","PHILHEALTH","PAGIBIG");
    private static final Set<String> PERIODS=Set.of("DAILY","WEEKLY","SEMI_MONTHLY","MONTHLY","ANNUAL");
    private static final Set<String> DAY_TYPES=Set.of("ORDINARY","REST_DAY","SPECIAL_HOLIDAY","SPECIAL_HOLIDAY_REST_DAY","REGULAR_HOLIDAY","REGULAR_HOLIDAY_REST_DAY");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final ObjectMapper mapper;

    public StatutoryRuleProposalController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,ObjectMapper mapper){this.jdbc=jdbc;this.tenants=tenants;this.mapper=mapper;}

    public record Submission(String ruleType,LocalDate effectiveFrom,Map<String,Object> payload){}
    public record Decision(boolean approve,String note){}
    public record Proposal(UUID id,UUID tenantId,String workspaceName,String ruleType,LocalDate effectiveFrom,String payload,String status,
                           OffsetDateTime submittedAt,UUID submittedBy,UUID reviewedBy,OffsetDateTime reviewedAt,
                           String reviewNote,UUID appliedRuleId){}

    @GetMapping("/api/reference/rule-proposals/mine")
    public List<Proposal> mine(@AuthenticationPrincipal HrmUserPrincipal user){
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT p.id,p.tenant_id,t.display_name AS workspace_name,p.rule_type,p.effective_from,p.payload::text,p.status,p.submitted_at,p.submitted_by,p.reviewed_by,p.reviewed_at,p.review_note,p.applied_rule_id
            FROM statutory_rule_proposal p JOIN tenant t ON t.id=p.tenant_id WHERE p.tenant_id=? ORDER BY p.submitted_at DESC LIMIT 100
            """,(rs,n)->map(rs),user.tenantId()));
    }

    @PostMapping("/api/reference/rule-proposals")
    public Proposal submit(@AuthenticationPrincipal HrmUserPrincipal user,@RequestBody Submission input){
        String type=upper(input.ruleType());
        if(!TYPES.contains(type)||input.effectiveFrom()==null||input.payload()==null) throw new BusinessException("Select a supported rule type, effective date, and complete rule values");
        JsonNode payload=mapper.valueToTree(input.payload());
        validate(type,payload,input.effectiveFrom());
        String json;
        try{json=mapper.writeValueAsString(payload);}catch(Exception ex){throw new BusinessException("Could not encode rule proposal");}
        return tenants.inTenant(user.tenantId(),()->{
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO statutory_rule_proposal(id,tenant_id,submitted_by,rule_type,effective_from,payload) VALUES (?,?,?,?,?,?::jsonb)",id,user.tenantId(),user.id(),type,input.effectiveFrom(),json);
            return jdbc.queryForObject("SELECT p.id,p.tenant_id,t.display_name AS workspace_name,p.rule_type,p.effective_from,p.payload::text,p.status,p.submitted_at,p.submitted_by,p.reviewed_by,p.reviewed_at,p.review_note,p.applied_rule_id FROM statutory_rule_proposal p JOIN tenant t ON t.id=p.tenant_id WHERE p.id=?",(rs,n)->map(rs),id);
        });
    }

    @GetMapping("/api/v1/platform-admin/statutory-rule-proposals")
    @Transactional(readOnly=true)
    public List<Proposal> queue(@AuthenticationPrincipal PlatformAdminPrincipal admin){
        platformScope();
        return jdbc.query("""
            SELECT p.id,p.tenant_id,t.display_name AS workspace_name,p.rule_type,p.effective_from,p.payload::text,p.status,p.submitted_at,p.submitted_by,p.reviewed_by,p.reviewed_at,p.review_note,p.applied_rule_id
            FROM statutory_rule_proposal p JOIN tenant t ON t.id=p.tenant_id WHERE p.status='PENDING' ORDER BY p.submitted_at
            """,(rs,n)->map(rs));
    }

    @PostMapping("/api/v1/platform-admin/statutory-rule-proposals/{id}/decision")
    @Transactional
    public Proposal decide(@AuthenticationPrincipal PlatformAdminPrincipal admin,@PathVariable UUID id,@RequestBody Decision decision){
        if(decision==null||decision.note()==null||decision.note().isBlank()||decision.note().length()>1000) throw new BusinessException("Enter a review note of 1 to 1000 characters");
        platformScope();
        Proposal proposal=jdbc.query("SELECT p.id,p.tenant_id,t.display_name AS workspace_name,p.rule_type,p.effective_from,p.payload::text,p.status,p.submitted_at,p.submitted_by,p.reviewed_by,p.reviewed_at,p.review_note,p.applied_rule_id FROM statutory_rule_proposal p JOIN tenant t ON t.id=p.tenant_id WHERE p.id=? FOR UPDATE OF p",(rs,n)->map(rs),id)
                .stream().findFirst().orElseThrow(()->new BusinessException("Rule proposal was not found"));
        if(!"PENDING".equals(proposal.status())) throw new BusinessException("This proposal has already been reviewed");
        UUID applied=null;
        if(decision.approve()){
            JsonNode p;
            try{p=mapper.readTree(proposal.payload());}catch(Exception ex){throw new BusinessException("Saved proposal data is invalid");}
            validate(proposal.ruleType(),p,proposal.effectiveFrom());
            ensureNoDuplicate(proposal.ruleType(),proposal.effectiveFrom(),p);
            applied=apply(proposal.ruleType(),proposal.effectiveFrom(),p);
        }
        jdbc.update("UPDATE statutory_rule_proposal SET status=?,reviewed_by=?,reviewed_at=now(),review_note=?,applied_rule_id=? WHERE id=? AND status='PENDING'",
                decision.approve()?"APPROVED":"REJECTED",admin.id(),decision.note().trim(),applied,id);
        return jdbc.queryForObject("SELECT p.id,p.tenant_id,t.display_name AS workspace_name,p.rule_type,p.effective_from,p.payload::text,p.status,p.submitted_at,p.submitted_by,p.reviewed_by,p.reviewed_at,p.review_note,p.applied_rule_id FROM statutory_rule_proposal p JOIN tenant t ON t.id=p.tenant_id WHERE p.id=?",(rs,n)->map(rs),id);
    }

    private UUID apply(String type,LocalDate date,JsonNode p){
        UUID id=UUID.randomUUID();
        switch(type){
            case "CONTRIBUTION" -> {
                String agency=text(p,"agency");
                LocalDate end=boundedContributionEnd(agency,date,date(p,"effectiveTo"));
                jdbc.update("UPDATE statutory_contribution_rule SET effective_to=? WHERE agency=? AND effective_from<? AND (effective_to IS NULL OR effective_to>=?)",date.minusDays(1),agency,date,date);
                jdbc.update("""
                    INSERT INTO statutory_contribution_rule(id,agency,effective_from,effective_to,employee_rate,employer_rate,min_base,max_base,msc_step,ec_low,ec_high,ec_threshold,mpf_threshold,low_income_threshold,low_income_employee_rate,source_reference)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,agency,date,end,num(p,"employeeRate"),num(p,"employerRate"),num(p,"minBase"),num(p,"maxBase"),num(p,"mscStep"),num(p,"ecLow"),num(p,"ecHigh"),num(p,"ecThreshold"),num(p,"mpfThreshold"),num(p,"lowIncomeThreshold"),num(p,"lowIncomeEmployeeRate"),text(p,"sourceReference"));
            }
            case "TAX_BRACKET" -> {
                String period=text(p,"periodType"),source=text(p,"sourceReference");
                for(JsonNode band:p.path("brackets")) jdbc.update("INSERT INTO withholding_tax_bracket(id,effective_from,period_type,lower_bound,upper_bound,base_tax,rate,source_reference) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),date,period,num(band,"lowerBound"),num(band,"upperBound"),num(band,"baseTax"),num(band,"rate"),source);
            }
            case "PREMIUM" -> jdbc.update("INSERT INTO premium_rule(id,day_type,worked_multiplier,ot_multiplier,unworked_multiplier,base_included_monthly,night_diff_rate,effective_from,source_reference) VALUES (?,?,?,?,?,?,?,?,?)",id,text(p,"dayType"),num(p,"workedMultiplier"),num(p,"otMultiplier"),num(p,"unworkedMultiplier"),p.path("baseIncludedMonthly").asBoolean(),num(p,"nightDiffRate"),date,text(p,"sourceReference"));
            case "WAGE_ORDER" -> {
                String region=text(p,"regionCode").toUpperCase(java.util.Locale.ROOT),sector=text(p,"sector");
                LocalDate end=boundedWageEnd(region,sector,date,date(p,"effectiveTo"));
                jdbc.update("UPDATE wage_order SET effective_to=? WHERE region_code=? AND sector=? AND effective_from<? AND (effective_to IS NULL OR effective_to>=?)",date.minusDays(1),region,sector,date,date);
                jdbc.update("INSERT INTO wage_order(id,region_code,sector,daily_rate,effective_from,effective_to,reference) VALUES (?,?,?,?,?,?,?)",id,region,sector,num(p,"dailyRate"),date,end,text(p,"reference"));
            }
            default -> throw new BusinessException("Unsupported rule type");
        }
        return id;
    }

    private static void validate(String type,JsonNode p,LocalDate date){
        if(p==null||!p.isObject()) throw new BusinessException("Rule values must be an object");
        String source=text(p,"sourceReference");
        switch(type){
            case "CONTRIBUTION" -> {
                require(AGENCIES.contains(text(p,"agency")),"Select SSS, PhilHealth, or Pag-IBIG");
                rate(p,"employeeRate"); rate(p,"employerRate"); nonnegative(p,"minBase"); nonnegative(p,"maxBase");
                optionalNonnegative(p,"mscStep"); optionalNonnegative(p,"ecLow"); optionalNonnegative(p,"ecHigh"); optionalNonnegative(p,"ecThreshold"); optionalNonnegative(p,"mpfThreshold"); optionalNonnegative(p,"lowIncomeThreshold"); optionalRate(p,"lowIncomeEmployeeRate");
                if(num(p,"maxBase").compareTo(num(p,"minBase"))<0) throw new BusinessException("Maximum contribution base must be at least the minimum");
                checkEnd(p,date);
            }
            case "TAX_BRACKET" -> {
                require(PERIODS.contains(text(p,"periodType")),"Select a supported withholding period");
                JsonNode bands=p.path("brackets");
                require(bands.isArray()&&!bands.isEmpty(),"Add all tax bands to the proposed table before submitting");
                BigDecimal expected=BigDecimal.ZERO;
                for(int i=0;i<bands.size();i++){
                    JsonNode band=bands.get(i); nonnegative(band,"lowerBound"); optionalNonnegative(band,"upperBound"); nonnegative(band,"baseTax"); rate(band,"rate");
                    if(num(band,"lowerBound").compareTo(expected)!=0) throw new BusinessException("Tax bands must be contiguous and start at zero");
                    if(!band.hasNonNull("upperBound")){
                        if(i!=bands.size()-1) throw new BusinessException("The open-ended tax band must be last");
                    }else{
                        BigDecimal upper=num(band,"upperBound");
                        if(upper.compareTo(num(band,"lowerBound"))<=0) throw new BusinessException("Upper tax-band bound must exceed its lower bound");
                        expected=upper;
                    }
                    if(i==bands.size()-1&&band.hasNonNull("upperBound")) throw new BusinessException("The final tax band must be open-ended");
                }
            }
            case "PREMIUM" -> {
                require(DAY_TYPES.contains(text(p,"dayType")),"Select a supported premium day type");
                nonnegative(p,"workedMultiplier"); nonnegative(p,"otMultiplier"); nonnegative(p,"unworkedMultiplier"); rate(p,"nightDiffRate");
            }
            case "WAGE_ORDER" -> {
                require(!text(p,"regionCode").isBlank(),"Enter a region code");
                require(Set.of("NON_AGRI","AGRI","DOMESTIC_WORKER").contains(text(p,"sector")),"Select a supported wage sector");
                require(num(p,"dailyRate").signum()>0,"Daily wage must be greater than zero"); checkEnd(p,date);
            }
            default -> throw new BusinessException("Unsupported rule type");
        }
        if(source.isBlank()&&!("WAGE_ORDER".equals(type)&&!text(p,"reference").isBlank())) throw new BusinessException("An official circular, government source URL, or wage-order reference is required");
    }

    private void platformScope(){jdbc.queryForObject("SELECT set_config('app.platform_admin','true',true)",String.class);}
    private void ensureNoDuplicate(String type,LocalDate date,JsonNode p){
        String sql; Object[] args;
        switch(type){
            case "CONTRIBUTION" -> {sql="SELECT count(*) FROM statutory_contribution_rule WHERE agency=? AND effective_from=?";args=new Object[]{text(p,"agency"),date};}
            case "TAX_BRACKET" -> {sql="SELECT count(*) FROM withholding_tax_bracket WHERE period_type=? AND effective_from=?";args=new Object[]{text(p,"periodType"),date};}
            case "PREMIUM" -> {sql="SELECT count(*) FROM premium_rule WHERE day_type=? AND effective_from=?";args=new Object[]{text(p,"dayType"),date};}
            case "WAGE_ORDER" -> {sql="SELECT count(*) FROM wage_order WHERE region_code=? AND sector=? AND effective_from=?";args=new Object[]{text(p,"regionCode").toUpperCase(java.util.Locale.ROOT),text(p,"sector"),date};}
            default -> throw new BusinessException("Unsupported rule type");
        }
        if(jdbc.queryForObject(sql,Integer.class,args)>0) throw new BusinessException("A rule already exists for this category and effective date");
    }
    private LocalDate boundedContributionEnd(String agency,LocalDate from,LocalDate requestedEnd){
        LocalDate next=jdbc.queryForObject("SELECT min(effective_from) FROM statutory_contribution_rule WHERE agency=? AND effective_from>?",LocalDate.class,agency,from);
        return next!=null&&(requestedEnd==null||!requestedEnd.isBefore(next))?next.minusDays(1):requestedEnd;
    }
    private LocalDate boundedWageEnd(String region,String sector,LocalDate from,LocalDate requestedEnd){
        LocalDate next=jdbc.queryForObject("SELECT min(effective_from) FROM wage_order WHERE region_code=? AND sector=? AND effective_from>?",LocalDate.class,region,sector,from);
        return next!=null&&(requestedEnd==null||!requestedEnd.isBefore(next))?next.minusDays(1):requestedEnd;
    }
    private static Proposal map(java.sql.ResultSet r)throws java.sql.SQLException{return new Proposal(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("workspace_name"),r.getString("rule_type"),r.getObject("effective_from",LocalDate.class),r.getString("payload"),r.getString("status"),r.getObject("submitted_at",OffsetDateTime.class),r.getObject("submitted_by",UUID.class),r.getObject("reviewed_by",UUID.class),r.getObject("reviewed_at",OffsetDateTime.class),r.getString("review_note"),r.getObject("applied_rule_id",UUID.class));}
    private static String upper(String s){return s==null?"":s.trim().toUpperCase(java.util.Locale.ROOT);}
    private static String text(JsonNode p,String key){return p.path(key).isMissingNode()||p.path(key).isNull()?"":p.path(key).asText().trim();}
    private static BigDecimal num(JsonNode p,String key){if(!p.path(key).isNumber()) throw new BusinessException("Enter a valid numeric value for "+key);return p.path(key).decimalValue();}
    private static LocalDate date(JsonNode p,String key){return p.path(key).isTextual()&&!p.path(key).asText().isBlank()?LocalDate.parse(p.path(key).asText()):null;}
    private static void require(boolean yes,String message){if(!yes) throw new BusinessException(message);}
    private static void nonnegative(JsonNode p,String key){if(num(p,key).signum()<0) throw new BusinessException(key+" cannot be negative");}
    private static void optionalNonnegative(JsonNode p,String key){if(p.hasNonNull(key))nonnegative(p,key);}
    private static void rate(JsonNode p,String key){BigDecimal n=num(p,key);if(n.signum()<0||n.compareTo(BigDecimal.ONE)>0)throw new BusinessException(key+" must be between 0 and 1");}
    private static void optionalRate(JsonNode p,String key){if(p.hasNonNull(key))rate(p,key);}
    private static void checkEnd(JsonNode p,LocalDate from){LocalDate to=date(p,"effectiveTo");if(to!=null&&to.isBefore(from))throw new BusinessException("Effective-to date cannot be before effective-from date");}
}
