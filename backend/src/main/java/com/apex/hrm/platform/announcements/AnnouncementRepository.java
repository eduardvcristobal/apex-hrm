package com.apex.hrm.platform.announcements;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
    @Query("select a from Announcement a where a.status = 'PUBLISHED' and a.publishedAt <= :now and (a.expiresOn is null or a.expiresOn >= :today)")
    Page<Announcement> findVisible(@Param("now") Instant now, @Param("today") LocalDate today, Pageable pageable);
}
