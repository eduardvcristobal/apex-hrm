package com.apex.hrm.platform.announcements;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "announcement")
@Getter
@Setter
public class Announcement extends BaseEntity {
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false, length = 16)
    private String priority = "NORMAL";

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(nullable = false)
    private boolean pinned;
}
