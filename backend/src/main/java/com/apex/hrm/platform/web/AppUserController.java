package com.apex.hrm.platform.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.platform.domain.AppUser;
import com.apex.hrm.platform.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app-users")
public class AppUserController extends CrudController<AppUser> {
    public AppUserController(AppUserRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
