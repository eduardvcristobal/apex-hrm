package com.apex.hrm.personnel;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController("saasEmployeeController")
@RequestMapping("/api/v1/personnel/employees")
public class EmployeeController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmployeeController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Employee(UUID id,UUID companyId,String employeeNumber,String firstName,
                           String lastName,LocalDate hireDate,String employmentStatus,String photoDataUrl,
                           UUID departmentId,String departmentName,UUID positionId,String positionName) {}
    public record SeedSummary(int companiesProcessed,int employeesCreated,int rolesSkipped) {}
    public record CreateEmployee(
        @NotNull UUID companyId,
        @NotBlank @Size(max=50) @Pattern(regexp="[A-Za-z0-9_-]+") String employeeNumber,
        @NotBlank @Size(max=120) String firstName,
        @NotBlank @Size(max=120) String lastName,
        @NotNull @PastOrPresent LocalDate hireDate
    ) {}
    @GetMapping
    public List<Employee> list(@AuthenticationPrincipal HrmUserPrincipal user,
             @RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT e.id,e.company_id,e.employee_number,e.first_name,e.last_name,e.hire_date,
                   e.employment_status,e.photo_data_url,e.department_id,d.department_name,
                   e.position_id,p.position_name
            FROM personnel_employee e
            LEFT JOIN personnel_department d ON d.tenant_id=e.tenant_id AND d.company_id=e.company_id AND d.id=e.department_id
            LEFT JOIN personnel_position p ON p.tenant_id=e.tenant_id AND p.company_id=e.company_id AND p.id=e.position_id
            WHERE e.tenant_id=? AND e.company_id=?
            ORDER BY e.last_name,e.first_name,e.employee_number LIMIT 200
            """,(rs,i)->row(rs),user.tenantId(),companyId));
    }

    /** Fill every currently unassigned role in every company chart in this tenant with clearly marked demo staff. */
    @PostMapping("/seed")
    public SeedSummary seed(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->{
            List<SeedRole> roles=jdbc.query("""
                SELECT n.id,n.company_id,n.node_type,n.name,n.title
                FROM organization_chart_node n
                JOIN company c ON c.tenant_id=n.tenant_id AND c.id=n.company_id
                WHERE n.tenant_id=? AND n.active AND n.employee_id IS NULL
                  AND n.node_type IN ('CEO','EXECUTIVE','DEPARTMENT','SECTION','TEAM','POSITION')
                ORDER BY n.company_id,n.sort_order,n.created_at,n.id
                FOR UPDATE OF n
                """,(rs,i)->new SeedRole(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
                    rs.getString("node_type"),rs.getString("name"),rs.getString("title")),user.tenantId());
            Integer roleCount=jdbc.queryForObject("""
                SELECT count(*) FROM organization_chart_node
                WHERE tenant_id=? AND active AND node_type IN ('CEO','EXECUTIVE','DEPARTMENT','SECTION','TEAM','POSITION')
                """,Integer.class,user.tenantId());
            List<UUID> companyIds=jdbc.query("SELECT id FROM company WHERE tenant_id=? ORDER BY created_at,id",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId());
            int skipped=(roleCount==null?0:roleCount)-roles.size();
            if(roles.isEmpty()) return new SeedSummary(companyIds.size(),0,skipped);

            String[][] names={{"Avery","Santos"},{"Jordan","Reyes"},{"Taylor","Cruz"},{"Morgan","Garcia"},
                {"Riley","Dela Cruz"},{"Cameron","Mendoza"},{"Quinn","Ramos"},{"Alex","Castillo"},
                {"Casey","Flores"},{"Jamie","Navarro"},{"Robin","Aquino"},{"Drew","Villanueva"},
                {"Sam","Rivera"},{"Skyler","Bautista"},{"Emerson","Torres"},{"Parker","Gonzales"},
                {"Reese","Fernandez"},{"Dakota","Lopez"},{"Kendall","Perez"},{"Rowan","Diaz"},
                {"Sydney","Morales"},{"Finley","Hernandez"},{"Hayden","Dizon"},{"Blake","Soriano"},
                {"Elliot","Aquino"},{"Harper","Manalo"},{"Logan","Santiago"},{"Micah","Domingo"},
                {"Noah","Valdez"},{"Aria","Cabrera"}};
            LocalDate hireDate=LocalDate.now();
            int created=0;
            for(int i=0;i<roles.size();i++) {
                SeedRole role=roles.get(i); String[] name=names[i%names.length];
                String firstName=name[0],lastName=name[1];
                if(i>=names.length) lastName=lastName+(i/names.length+1);
                String number="DEMO-"+role.companyId().toString().substring(0,8)+"-%04d".formatted(i+1);
                UUID positionId=ensureDemoPosition(user.tenantId(),role.companyId(),role.title()==null?role.name():role.title());
                UUID employeeId=jdbc.queryForObject("""
                    INSERT INTO personnel_employee(tenant_id,company_id,employee_number,first_name,last_name,hire_date,position_id)
                    VALUES(?,?,?,?,?,?,?) RETURNING id
                    """,UUID.class,user.tenantId(),role.companyId(),number,firstName,lastName,hireDate,positionId);
                jdbc.update("""
                    INSERT INTO personnel_employment_history(tenant_id,employee_id,effective_date,employment_type,
                      employment_status,job_title,reason)
                    VALUES(?,?,?,'REGULAR','ACTIVE',?,'DEMO_SEED')
                    """,user.tenantId(),employeeId,hireDate,role.title()==null?role.name():role.title());
                jdbc.update("""
                    INSERT INTO personnel_assignment_history(tenant_id,employee_id,company_id,position_id,effective_date,reason,recorded_by)
                    VALUES(?,?,?,?,?,'DEMO_SEED',?)
                    """,user.tenantId(),employeeId,role.companyId(),positionId,hireDate,user.id());
                jdbc.update("UPDATE organization_chart_node SET employee_id=?,updated_at=now() WHERE tenant_id=? AND company_id=? AND id=? AND employee_id IS NULL",
                    employeeId,user.tenantId(),role.companyId(),role.nodeId());
                jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'DEMO_EMPLOYEE_SEEDED','personnel_employee',?)",
                    user.tenantId(),user.id(),employeeId);
                created++;
            }
            return new SeedSummary(companyIds.size(),created,skipped);
        });
    }

    private UUID ensureDemoPosition(UUID tenantId,UUID companyId,String title) {
        String base=title.toUpperCase().replaceAll("[^A-Z0-9]+","-").replaceAll("^-|-$","");
        if(base.isBlank()) base="DEMO-ROLE";
        if(base.length()>30) base=base.substring(0,30);
        String code="DEMO-"+base;
        List<UUID> found=jdbc.query("SELECT id FROM personnel_position WHERE tenant_id=? AND company_id=? AND position_code=?",
            (rs,i)->rs.getObject(1,UUID.class),tenantId,companyId,code);
        if(!found.isEmpty()) return found.get(0);
        List<UUID> inserted=jdbc.query("""
            INSERT INTO personnel_position(tenant_id,company_id,position_code,position_name)
            VALUES(?,?,?,?) ON CONFLICT(tenant_id,company_id,position_code) DO UPDATE SET position_name=EXCLUDED.position_name
            RETURNING id
            """,(rs,i)->rs.getObject(1,UUID.class),tenantId,companyId,code,title.length()>160?title.substring(0,160):title);
        return inserted.get(0);
    }

    private record SeedRole(UUID nodeId,UUID companyId,String nodeType,String name,String title) {}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Employee create(@AuthenticationPrincipal HrmUserPrincipal user,
             @Valid @RequestBody CreateEmployee input) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),input.companyId());
            if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            Employee employee=jdbc.queryForObject("""
                INSERT INTO personnel_employee
                  (tenant_id,company_id,employee_number,first_name,last_name,hire_date)
                VALUES(?,?,?,?,?,?)
                RETURNING id,company_id,employee_number,first_name,last_name,hire_date,employment_status,
                          photo_data_url,department_id,NULL::varchar AS department_name,position_id,
                          NULL::varchar AS position_name
                """,(rs,i)->row(rs),user.tenantId(),input.companyId(),input.employeeNumber(),
                input.firstName().trim(),input.lastName().trim(),input.hireDate());
            jdbc.update("""
                INSERT INTO personnel_employment_history
                  (tenant_id,employee_id,effective_date,employment_type,employment_status,reason)
                VALUES(?,? ,?,'REGULAR','ACTIVE','INITIAL_HIRE')
                """,user.tenantId(),employee.id(),input.hireDate());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYEE_CREATED','personnel_employee',?)
                """,user.tenantId(),user.id(),employee.id());
            return employee;
        });
    }
    private static Employee row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Employee(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("employee_number"),rs.getString("first_name"),rs.getString("last_name"),
            rs.getObject("hire_date",LocalDate.class),rs.getString("employment_status"),rs.getString("photo_data_url"),
            rs.getObject("department_id",UUID.class),rs.getString("department_name"),
            rs.getObject("position_id",UUID.class),rs.getString("position_name"));
    }
}
