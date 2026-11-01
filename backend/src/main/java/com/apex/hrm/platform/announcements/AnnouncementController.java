package com.apex.hrm.platform.announcements;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.security.WorkspaceAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "IMPORTANT", "URGENT");
    private final AnnouncementRepository repository;
    private final WorkspaceAuditService audit;

    public AnnouncementController(AnnouncementRepository repository, WorkspaceAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record AnnouncementInput(@NotBlank @Size(max = 200) String title,
                                    @NotBlank @Size(max = 20000) String body,
                                    @Pattern(regexp = "NORMAL|IMPORTANT|URGENT") String priority,
                                    LocalDate expiresOn, boolean pinned) {}

    @GetMapping
    public Page<Announcement> list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        HrmUserPrincipal actor = actor(authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        if (canManage(authentication)) return repository.findAll(pageable);
        return repository.findVisible(Instant.now(), LocalDate.now(ZoneOffset.UTC), pageable);
    }

    @GetMapping("/{id}")
    public Announcement get(@PathVariable UUID id, Authentication authentication) {
        Announcement announcement = find(id);
        if (!canManage(authentication) && !isVisible(announcement)) throw notFound();
        return announcement;
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Announcement create(@Valid @RequestBody AnnouncementInput input, Authentication authentication) {
        HrmUserPrincipal actor = actor(authentication);
        validateInput(input);
        Announcement item = new Announcement();
        item.setAuthorId(actor.id());
        apply(item, input);
        item.setStatus("DRAFT");
        Announcement saved = repository.save(item);
        audit.record(actor.tenantId(), actor.id(), "ANNOUNCEMENT_CREATED", "announcement", saved.getId());
        return saved;
    }

    @PutMapping("/{id}")
    public Announcement update(@PathVariable UUID id, @Valid @RequestBody AnnouncementInput input,
                               Authentication authentication) {
        HrmUserPrincipal actor = actor(authentication);
        validateInput(input);
        Announcement item = find(id);
        if ("ARCHIVED".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Archived announcements cannot be edited");
        apply(item, input);
        Announcement saved = repository.save(item);
        audit.record(actor.tenantId(), actor.id(), "ANNOUNCEMENT_UPDATED", "announcement", saved.getId());
        return saved;
    }

    @PostMapping("/{id}/publish")
    public Announcement publish(@PathVariable UUID id, Authentication authentication) {
        HrmUserPrincipal actor = actor(authentication);
        Announcement item = find(id);
        if (!"DRAFT".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafts can be published");
        if (item.getExpiresOn() != null && item.getExpiresOn().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry date must be today or later");
        }
        item.setStatus("PUBLISHED");
        item.setPublishedAt(Instant.now());
        Announcement saved = repository.save(item);
        audit.record(actor.tenantId(), actor.id(), "ANNOUNCEMENT_PUBLISHED", "announcement", saved.getId());
        return saved;
    }

    @PostMapping("/{id}/archive")
    public Announcement archive(@PathVariable UUID id, Authentication authentication) {
        HrmUserPrincipal actor = actor(authentication);
        Announcement item = find(id);
        if ("ARCHIVED".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Announcement is already archived");
        item.setStatus("ARCHIVED");
        Announcement saved = repository.save(item);
        audit.record(actor.tenantId(), actor.id(), "ANNOUNCEMENT_ARCHIVED", "announcement", saved.getId());
        return saved;
    }

    private void apply(Announcement item, AnnouncementInput input) {
        item.setTitle(input.title().trim());
        item.setBody(input.body().trim());
        item.setPriority(input.priority() == null ? "NORMAL" : input.priority());
        item.setExpiresOn(input.expiresOn());
        item.setPinned(input.pinned());
    }

    private void validateInput(AnnouncementInput input) {
        if (!PRIORITIES.contains(input.priority() == null ? "NORMAL" : input.priority())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported announcement priority");
        }
    }

    private Announcement find(UUID id) {
        return repository.findById(id).orElseThrow(AnnouncementController::notFound);
    }

    private boolean isVisible(Announcement item) {
        return "PUBLISHED".equals(item.getStatus()) && item.getPublishedAt() != null
                && !item.getPublishedAt().isAfter(Instant.now())
                && (item.getExpiresOn() == null || !item.getExpiresOn().isBefore(LocalDate.now(ZoneOffset.UTC)));
    }

    private boolean canManage(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_TENANT_ADMIN") || a.getAuthority().equals("ROLE_HR_ADMIN"));
    }

    private HrmUserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof HrmUserPrincipal user) return user;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workspace account required");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found");
    }
}
