package com.apex.hrm.common;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Read-only endpoints. Used for records that are only changed through workflow services. */
public abstract class ReadController<T> {
    protected final JpaRepository<T, UUID> repo;

    protected ReadController(JpaRepository<T, UUID> repo) {
        this.repo = repo;
    }

    @GetMapping
    public Page<T> list(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @GetMapping("/{id}")
    public T get(@PathVariable UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Record", id));
    }
}
