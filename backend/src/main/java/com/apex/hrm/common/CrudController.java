package com.apex.hrm.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Generic CRUD for master/config data. PUT is a partial update (only the supplied fields change). */
public abstract class CrudController<T> extends ReadController<T> {
    private static final Set<String> PROTECTED = Set.of("id", "tenantId", "createdAt", "updatedAt", "version");
    private final ObjectMapper mapper;

    protected CrudController(JpaRepository<T, UUID> repo, ObjectMapper mapper) {
        super(repo);
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public T create(@RequestBody T body) {
        return repo.save(body);
    }

    @PutMapping("/{id}")
    public T update(@PathVariable UUID id, @RequestBody Map<String, Object> patch) {
        T entity = get(id);
        patch.keySet().removeAll(PROTECTED);
        try {
            mapper.updateValue(entity, patch);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid update payload: " + e.getOriginalMessage());
        }
        return repo.save(entity);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repo.delete(get(id));
    }
}
