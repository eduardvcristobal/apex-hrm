package com.apex.hrm.platform.company;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/organization-chart")
public class OrganizationChartController {
    private static final Set<String> NODE_TYPES = Set.of("OWNER", "CEO", "EXECUTIVE", "DEPARTMENT", "SECTION", "TEAM", "POSITION", "OTHER");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public OrganizationChartController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record Node(UUID id, UUID companyId, UUID parentId, String nodeType, String name, String title,
                       UUID employeeId, String employeeName, String photoDataUrl, String employeePhotoDataUrl,
                       int sortOrder, boolean active) {}
    public record CreateNode(@NotNull String nodeType, @NotBlank @Size(max=200) String name,
                             @Size(max=160) String title, UUID parentId, UUID employeeId, String photoDataUrl, Integer sortOrder) {}
    public record UpdateNode(@NotNull String nodeType, @NotBlank @Size(max=200) String name,
                             @Size(max=160) String title, UUID parentId, UUID employeeId, String photoDataUrl, Integer sortOrder, boolean active) {}
    public record SeedRequest(@NotBlank @Size(max=40) String template, @Size(max=200) String ownerName) {}
    private record SeedNode(String type, String name, String title, List<SeedNode> children) {}

    @GetMapping
    public List<Node> list(@PathVariable UUID companyId, @AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> {
            requireCompany(user.tenantId(), companyId);
            return jdbc.query("""
                SELECT n.id,n.company_id,n.parent_id,n.node_type,n.name,n.title,n.employee_id,n.photo_data_url,e.photo_data_url employee_photo_data_url,
                       CASE WHEN e.id IS NULL THEN NULL ELSE concat_ws(' ',e.first_name,e.last_name) END employee_name,
                       n.sort_order,n.active
                FROM organization_chart_node n
                LEFT JOIN personnel_employee e ON e.tenant_id=n.tenant_id AND e.company_id=n.company_id AND e.id=n.employee_id
                WHERE n.tenant_id=? AND n.company_id=?
                ORDER BY n.sort_order,n.created_at,n.id
                """, (rs, i) -> new Node(rs.getObject("id", UUID.class),rs.getObject("company_id", UUID.class),
                    rs.getObject("parent_id", UUID.class),rs.getString("node_type"),rs.getString("name"),
                    rs.getString("title"),rs.getObject("employee_id", UUID.class),rs.getString("employee_name"),rs.getString("photo_data_url"),rs.getString("employee_photo_data_url"),
                    rs.getInt("sort_order"),rs.getBoolean("active")), user.tenantId(), companyId);
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Node create(@PathVariable UUID companyId, @AuthenticationPrincipal HrmUserPrincipal user,
                       @Valid @RequestBody CreateNode input) {
        return tenants.inTenant(user.tenantId(), () -> {
            requireCompany(user.tenantId(), companyId);
            validateType(input.nodeType());
            validateParent(user.tenantId(),companyId,input.nodeType(),input.parentId(),null);
            validateEmployee(user.tenantId(),companyId,input.employeeId());
            String photo=validatePhoto(input.photoDataUrl());
            Node node = jdbc.queryForObject("""
                INSERT INTO organization_chart_node(tenant_id,company_id,parent_id,node_type,name,title,employee_id,photo_data_url,sort_order)
                VALUES(?,?,?,?,?,?,?,?,?)
                RETURNING id,company_id,parent_id,node_type,name,title,employee_id,NULL::text employee_name,photo_data_url,sort_order,active
                """,(rs,i)->new Node(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
                    rs.getObject("parent_id",UUID.class),rs.getString("node_type"),rs.getString("name"),rs.getString("title"),
                    rs.getObject("employee_id",UUID.class),null,rs.getString("photo_data_url"),null,rs.getInt("sort_order"),rs.getBoolean("active")),
                user.tenantId(),companyId,input.parentId(),input.nodeType(),input.name().trim(),clean(input.title()),
                input.employeeId(),photo,input.sortOrder()==null?0:input.sortOrder());
            audit(user,"ORG_CHART_NODE_CREATED",node.id());
            return node;
        });
    }

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Node> seed(@PathVariable UUID companyId, @AuthenticationPrincipal HrmUserPrincipal user,
                           @Valid @RequestBody SeedRequest input) {
        return tenants.inTenant(user.tenantId(), () -> {
            requireCompany(user.tenantId(),companyId);
            SeedNode template=seedTemplate(input.template());
            List<Node> roots=jdbc.query("SELECT id,company_id,parent_id,node_type,name,title,employee_id,NULL::text employee_name,photo_data_url,sort_order,active FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND parent_id IS NULL",
                (rs,i)->new Node(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),rs.getObject("parent_id",UUID.class),
                    rs.getString("node_type"),rs.getString("name"),rs.getString("title"),rs.getObject("employee_id",UUID.class),
                    rs.getString("employee_name"),rs.getString("photo_data_url"),null,rs.getInt("sort_order"),rs.getBoolean("active")),user.tenantId(),companyId);
            Integer existingChildren=jdbc.queryForObject("SELECT count(*) FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND parent_id IS NOT NULL",
                Integer.class,user.tenantId(),companyId);
            if(existingChildren!=null&&existingChildren>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Starter structures can only be seeded before adding chart nodes. Remove the current structure first.");

            List<Node> result=new ArrayList<>(roots);
            Node owner;
            if(roots.isEmpty()) {
                String companyOwner=jdbc.queryForObject("SELECT COALESCE(NULLIF(owner_name,''),'Owner') FROM company WHERE tenant_id=? AND id=?",
                    String.class,user.tenantId(),companyId);
                String name=clean(input.ownerName());
                if(name==null) name=clean(companyOwner);
                if(name==null) name="Owner";
                owner=insertSeedNode(user.tenantId(),companyId,null,"OWNER",name,"Owner",0);
                result.add(owner);
            } else owner=roots.get(0);

            insertSeedBranch(user.tenantId(),companyId,owner.id(),template,0,result);
            audit(user,"ORG_CHART_TEMPLATE_SEEDED",owner.id());
            return result;
        });
    }

    private Node insertSeedBranch(UUID tenantId,UUID companyId,UUID parentId,SeedNode branch,int order,List<Node> result) {
        Node node=insertSeedNode(tenantId,companyId,parentId,branch.type(),branch.name(),branch.title(),order);
        result.add(node);
        for(int i=0;i<branch.children().size();i++) insertSeedBranch(tenantId,companyId,node.id(),branch.children().get(i),i,result);
        return node;
    }

    private Node insertSeedNode(UUID tenantId,UUID companyId,UUID parentId,String type,String name,String title,int order) {
        return jdbc.queryForObject("""
            INSERT INTO organization_chart_node(tenant_id,company_id,parent_id,node_type,name,title,employee_id,photo_data_url,sort_order)
            VALUES(?,?,?,?,?,?,NULL,NULL,?)
            RETURNING id,company_id,parent_id,node_type,name,title,employee_id,NULL::text employee_name,photo_data_url,sort_order,active
            """,(rs,i)->new Node(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),rs.getObject("parent_id",UUID.class),
                rs.getString("node_type"),rs.getString("name"),rs.getString("title"),rs.getObject("employee_id",UUID.class),
                rs.getString("employee_name"),rs.getString("photo_data_url"),null,rs.getInt("sort_order"),rs.getBoolean("active")),
            tenantId,companyId,parentId,type,name,title,order);
    }

    private static SeedNode seedTemplate(String key) {
        return switch(key.trim().toUpperCase()) {
            case "SMALL_BUSINESS" -> node("CEO","CEO","Chief Executive Officer",
                node("DEPARTMENT","Operations","Operations Manager",
                    node("TEAM","Customer Service","Customer Service Lead",node("POSITION","Customer Service Representative","Frontline support")),
                    node("TEAM","Service Delivery","Service Delivery Lead",node("POSITION","Operations Coordinator","Daily operations"))),
                node("DEPARTMENT","Finance & Administration","Finance and Administration Manager",
                    node("SECTION","Finance","Finance Lead",node("POSITION","Accountant","Accounting and reporting")),
                    node("SECTION","People & Administration","People Operations Lead",node("POSITION","HR Generalist","People and office support"))),
                node("DEPARTMENT","Sales & Marketing","Commercial Manager",
                    node("TEAM","Business Development","Business Development Lead",node("POSITION","Account Executive","New business")),
                    node("TEAM","Marketing","Marketing Lead",node("POSITION","Marketing Specialist","Campaigns and communications"))));
            case "FUNCTIONAL" -> node("CEO","CEO","Chief Executive Officer",
                node("EXECUTIVE","Operations","Chief Operating Officer",
                    node("DEPARTMENT","Service Delivery","Operations Director",
                        node("SECTION","Customer Operations","Customer Operations Manager",
                            node("TEAM","Customer Support","Support Team Lead",node("POSITION","Customer Support Specialist","Frontline service")),
                            node("TEAM","Quality & Escalations","Quality Lead",node("POSITION","Quality Analyst","Service quality")))),
                    node("DEPARTMENT","People & Culture","Chief People Officer",
                        node("SECTION","People Operations","People Operations Manager",
                            node("TEAM","Talent Acquisition","Talent Lead",node("POSITION","Recruiter","Hiring and onboarding")),
                            node("TEAM","Employee Services","HR Services Lead",node("POSITION","HR Coordinator","Employee support"))))),
                node("EXECUTIVE","Finance","Chief Financial Officer",
                    node("DEPARTMENT","Finance & Accounting","Finance Director",
                        node("SECTION","Accounting","Accounting Manager",node("TEAM","General Ledger","Senior Accountant",node("POSITION","Accountant","Financial records"))),
                        node("SECTION","Planning & Analysis","FP&A Manager",node("POSITION","Financial Analyst","Planning and analysis"))),
                    node("DEPARTMENT","Procurement","Procurement Manager",node("TEAM","Purchasing","Purchasing Lead",node("POSITION","Procurement Specialist","Vendor and purchasing support")))),
                node("EXECUTIVE","Commercial","Chief Commercial Officer",
                    node("DEPARTMENT","Sales","Sales Director",
                        node("TEAM","Business Development","Business Development Lead",node("POSITION","Account Executive","New business")),
                        node("TEAM","Account Management","Account Management Lead",node("POSITION","Account Manager","Customer growth"))),
                    node("DEPARTMENT","Marketing","Marketing Director",node("SECTION","Brand & Digital","Marketing Manager",node("POSITION","Marketing Specialist","Brand and campaigns")))),
                node("EXECUTIVE","Technology","Chief Technology Officer",
                    node("DEPARTMENT","IT & Business Systems","IT Director",
                        node("SECTION","Systems & Support","IT Manager",node("TEAM","Service Desk","Service Desk Lead",node("POSITION","IT Support Specialist","User and device support"))),
                        node("SECTION","Business Applications","Applications Manager",node("POSITION","Systems Analyst","Business systems")))));
            case "TECHNOLOGY" -> node("CEO","CEO","Chief Executive Officer",
                node("EXECUTIVE","Product & Engineering","Chief Technology Officer",
                    node("DEPARTMENT","Product","VP of Product",
                        node("TEAM","Product Management","Product Lead",node("POSITION","Product Manager","Product planning")),
                        node("TEAM","Product Design","Design Lead",node("POSITION","Product Designer","User experience"))),
                    node("DEPARTMENT","Engineering","Engineering Director",
                        node("SECTION","Application Engineering","Engineering Manager",
                            node("TEAM","Web Platform","Tech Lead",node("POSITION","Software Engineer","Web applications")),
                            node("TEAM","Quality Engineering","QA Lead",node("POSITION","QA Engineer","Product quality"))),
                        node("SECTION","Infrastructure & Security","Platform Lead",node("POSITION","Platform Engineer","Cloud and security"))),
                    node("DEPARTMENT","Data & Analytics","Data Director",node("TEAM","Data Platform","Data Lead",node("POSITION","Data Analyst","Reporting and insights")))),
                node("EXECUTIVE","Revenue","Chief Revenue Officer",
                    node("DEPARTMENT","Sales","Sales Director",node("TEAM","Business Development","Sales Lead",node("POSITION","Account Executive","New business"))),
                    node("DEPARTMENT","Marketing","Marketing Director",node("TEAM","Growth Marketing","Growth Lead",node("POSITION","Growth Specialist","Demand generation"))),
                    node("DEPARTMENT","Customer Success","Customer Success Director",node("TEAM","Customer Support","Support Lead",node("POSITION","Support Specialist","Customer assistance")))),
                node("EXECUTIVE","Corporate Services","Chief Operating Officer",
                    node("DEPARTMENT","People & Culture","People Director",node("POSITION","HR Business Partner","People operations")),
                    node("DEPARTMENT","Finance & Legal","Finance Director",node("POSITION","Finance Specialist","Finance and compliance"))));
            case "HEALTHCARE" -> node("CEO","CEO","Chief Executive Officer",
                node("EXECUTIVE","Clinical Services","Chief Medical Officer",
                    node("DEPARTMENT","Clinical Operations","Clinical Director",
                        node("SECTION","Primary Care","Primary Care Manager",node("TEAM","Clinical Team A","Clinical Team Lead",node("POSITION","Physician","Patient care")),node("POSITION","Registered Nurse","Clinical support")),
                        node("SECTION","Diagnostics & Treatment","Diagnostics Manager",node("POSITION","Medical Technologist","Diagnostic services"))),
                    node("DEPARTMENT","Quality & Patient Safety","Quality Director",node("TEAM","Clinical Quality","Quality Lead",node("POSITION","Quality Coordinator","Clinical standards")))),
                node("EXECUTIVE","Patient Services","Patient Services Director",
                    node("DEPARTMENT","Patient Access","Patient Access Manager",node("TEAM","Reception & Scheduling","Front Office Lead",node("POSITION","Patient Services Representative","Patient coordination"))),
                    node("DEPARTMENT","Health Information","Records Manager",node("TEAM","Medical Records","Records Lead",node("POSITION","Health Records Clerk","Records administration")))),
                node("EXECUTIVE","Corporate Services","Chief Operating Officer",
                    node("DEPARTMENT","People & Administration","HR Manager",node("POSITION","HR Generalist","People operations")),
                    node("DEPARTMENT","Finance & Billing","Finance Manager",node("TEAM","Billing","Billing Lead",node("POSITION","Billing Specialist","Claims and billing"))),
                    node("DEPARTMENT","Facilities & IT","Operations Manager",node("POSITION","IT Support Specialist","Systems support"))));
            case "RETAIL" -> node("CEO","CEO","Chief Executive Officer",
                node("EXECUTIVE","Store Operations","Chief Operating Officer",
                    node("DEPARTMENT","Retail Operations","Retail Director",
                        node("SECTION","Area Operations","Area Manager",node("TEAM","Store Management","Store Manager",node("POSITION","Store Associate","Customer-facing operations"))),
                        node("SECTION","Loss Prevention & Standards","Operations Manager",node("POSITION","Retail Auditor","Store standards"))),
                    node("DEPARTMENT","Supply Chain","Supply Chain Director",node("SECTION","Warehouse & Distribution","Distribution Manager",node("POSITION","Inventory Coordinator","Stock movement")))),
                node("EXECUTIVE","Merchandising & Commercial","Chief Commercial Officer",
                    node("DEPARTMENT","Merchandising","Merchandising Director",node("TEAM","Category Management","Category Lead",node("POSITION","Merchandiser","Assortment and pricing"))),
                    node("DEPARTMENT","Sales & Marketing","Commercial Director",node("SECTION","Marketing","Marketing Manager",node("POSITION","Marketing Specialist","Promotions and campaigns")))),
                node("EXECUTIVE","Corporate Services","Chief Financial Officer",
                    node("DEPARTMENT","Finance","Finance Director",node("TEAM","Accounting","Accounting Lead",node("POSITION","Accountant","Financial reporting"))),
                    node("DEPARTMENT","People & Culture","People Director",node("POSITION","HR Generalist","Hiring and employee support"))));
            case "PROFESSIONAL_SERVICES" -> node("CEO","CEO","Chief Executive Officer",
                node("EXECUTIVE","Client Delivery","Chief Delivery Officer",
                    node("DEPARTMENT","Professional Services","Services Director",
                        node("SECTION","Consulting","Consulting Lead",node("TEAM","Advisory","Engagement Manager",node("POSITION","Consultant","Client delivery"))),
                        node("SECTION","Implementation","Implementation Lead",node("TEAM","Project Delivery","Project Manager",node("POSITION","Implementation Specialist","Client implementation")))),
                    node("DEPARTMENT","Client Success","Client Success Director",node("TEAM","Account Services","Account Lead",node("POSITION","Client Success Manager","Customer outcomes")))),
                node("EXECUTIVE","Growth","Chief Growth Officer",
                    node("DEPARTMENT","Sales","Sales Director",node("TEAM","Business Development","BD Lead",node("POSITION","Account Executive","New business"))),
                    node("DEPARTMENT","Marketing","Marketing Director",node("POSITION","Marketing Specialist","Brand and demand generation"))),
                node("EXECUTIVE","Corporate Services","Chief Operating Officer",
                    node("DEPARTMENT","People & Culture","People Director",node("POSITION","HR Business Partner","People operations")),
                    node("DEPARTMENT","Finance & Administration","Finance Director",node("TEAM","Finance Operations","Finance Lead",node("POSITION","Accountant","Finance and administration")))));
            case "NONPROFIT" -> node("CEO","Executive Director","Executive Director",
                node("EXECUTIVE","Programs","Chief Program Officer",
                    node("DEPARTMENT","Program Delivery","Program Director",node("SECTION","Community Programs","Program Manager",node("TEAM","Field Operations","Field Lead",node("POSITION","Program Officer","Community delivery"))))),
                node("EXECUTIVE","Development & Partnerships","Development Director",
                    node("DEPARTMENT","Fundraising","Fundraising Manager",node("TEAM","Donor Relations","Donor Lead",node("POSITION","Development Officer","Fundraising and stewardship"))),
                    node("DEPARTMENT","Communications","Communications Manager",node("POSITION","Communications Specialist","Outreach and communications"))),
                node("EXECUTIVE","Operations & Finance","Chief Operating Officer",
                    node("DEPARTMENT","Finance & Compliance","Finance Manager",node("POSITION","Finance Officer","Accounting and compliance")),
                    node("DEPARTMENT","People & Administration","People Manager",node("POSITION","HR Generalist","People and office operations"))));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a supported organization template");
        };
    }

    private static SeedNode node(String type,String name,String title,SeedNode... children) {
        return new SeedNode(type,name,title,List.of(children));
    }

    @PutMapping("/{nodeId}")
    public Node update(@PathVariable UUID companyId, @PathVariable UUID nodeId,
                       @AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody UpdateNode input) {
        return tenants.inTenant(user.tenantId(), () -> {
            validateType(input.nodeType());
            List<UUID> parents=jdbc.query("SELECT parent_id FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),companyId,nodeId);
            if(parents.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization chart node not found");
            validateParent(user.tenantId(),companyId,input.nodeType(),input.parentId(),nodeId);
            validateNoCycle(user.tenantId(),companyId,nodeId,input.parentId());
            validateEmployee(user.tenantId(),companyId,input.employeeId());
            String photo=validatePhoto(input.photoDataUrl());
            List<Node> changed=jdbc.query("""
                UPDATE organization_chart_node SET parent_id=?,node_type=?,name=?,title=?,employee_id=?,photo_data_url=?,sort_order=?,active=?,updated_at=now()
                WHERE tenant_id=? AND company_id=? AND id=?
                RETURNING id,company_id,parent_id,node_type,name,title,employee_id,NULL::text employee_name,photo_data_url,sort_order,active
                """,(rs,i)->new Node(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
                    rs.getObject("parent_id",UUID.class),rs.getString("node_type"),rs.getString("name"),rs.getString("title"),
                    rs.getObject("employee_id",UUID.class),null,rs.getString("photo_data_url"),null,rs.getInt("sort_order"),rs.getBoolean("active")),
                input.parentId(),input.nodeType(),input.name().trim(),clean(input.title()),input.employeeId(),photo,input.sortOrder()==null?0:input.sortOrder(),input.active(),
                user.tenantId(),companyId,nodeId);
            audit(user,"ORG_CHART_NODE_UPDATED",nodeId);
            return changed.get(0);
        });
    }

    @DeleteMapping("/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID companyId, @PathVariable UUID nodeId,
                       @AuthenticationPrincipal HrmUserPrincipal user) {
        tenants.inTenant(user.tenantId(), () -> {
            requireCompany(user.tenantId(),companyId);
            List<UUID> assignments=jdbc.query("SELECT employee_id FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),companyId,nodeId);
            if(assignments.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization chart node not found");
            if(assignments.get(0)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Unassign the employee before removing this node so personnel history remains intact");
            Integer children=jdbc.queryForObject("SELECT count(*) FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND parent_id=?",
                Integer.class,user.tenantId(),companyId,nodeId);
            if(children!=null&&children>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Move or remove child nodes before deleting this node");
            int deleted=jdbc.update("DELETE FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND id=?",
                user.tenantId(),companyId,nodeId);
            if(deleted==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization chart node not found");
            audit(user,"ORG_CHART_NODE_DELETED",nodeId);
            return null;
        });
    }

    private void requireCompany(UUID tenantId, UUID companyId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",Integer.class,tenantId,companyId);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
    }
    private void validateParent(UUID tenantId, UUID companyId, String type, UUID parentId, UUID nodeId) {
        if(parentId==null) {
            if(!"OWNER".equals(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The organization owner must be the chart root");
            return;
        }
        if("OWNER".equals(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Owner must be the chart root");
        List<String> parents=jdbc.query("SELECT node_type FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND id=? AND active",
            (rs,i)->rs.getString(1),tenantId,companyId,parentId);
        if(parents.isEmpty()||parentId.equals(nodeId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select a valid active parent node");
        if("CEO".equals(type)&&!"OWNER".equals(parents.get(0))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CEO must report directly to the owner");
        if("OWNER".equals(parents.get(0))) return;
        if("CEO".equals(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CEO must report directly to the owner");
    }
    private void validateEmployee(UUID tenantId,UUID companyId,UUID employeeId) {
        if(employeeId==null) return;
        Integer count=jdbc.queryForObject("SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND company_id=? AND id=?",
            Integer.class,tenantId,companyId,employeeId);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an employee from this legal entity");
    }
    private static String validatePhoto(String dataUrl) {
        if(dataUrl==null||dataUrl.isBlank()) return null;
        var matcher=java.util.regex.Pattern.compile("^data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]*={0,2})$").matcher(dataUrl);
        if(!matcher.matches()||dataUrl.length()>360_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a PNG, JPG, or WebP photo no larger than 250 KB.");
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(matcher.group(2)); }
        catch(IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a valid image file."); }
        if(bytes.length==0||bytes.length>250*1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a photo no larger than 250 KB.");
        String type=matcher.group(1);
        boolean valid=type.equals("image/png")&&bytes.length>=8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71
            ||type.equals("image/jpeg")&&bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255
            ||type.equals("image/webp")&&bytes.length>=12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'&&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P';
        if(!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The uploaded file is not a valid PNG, JPG, or WebP image.");
        return dataUrl;
    }
    private static void validateType(String type) {
        if(type==null||!NODE_TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid organization chart node type");
    }
    private void validateNoCycle(UUID tenantId,UUID companyId,UUID nodeId,UUID parentId) {
        if(parentId==null) return;
        Integer cycle=jdbc.queryForObject("""
            WITH RECURSIVE descendants(id) AS (
                SELECT id FROM organization_chart_node WHERE tenant_id=? AND company_id=? AND id=?
                UNION
                SELECT child.id FROM organization_chart_node child JOIN descendants parent ON child.parent_id=parent.id
                WHERE child.tenant_id=? AND child.company_id=?
            ) SELECT count(*) FROM descendants WHERE id=?
            """,Integer.class,tenantId,companyId,nodeId,tenantId,companyId,parentId);
        if(cycle!=null&&cycle>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Moving this node would create a reporting cycle");
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'organization_chart_node',?)",
            user.tenantId(),user.id(),action,id);
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
}
