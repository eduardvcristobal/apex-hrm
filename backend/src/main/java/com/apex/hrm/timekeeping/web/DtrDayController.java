package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/dtr-days", "/api/dtr-daies"})
public class DtrDayController extends ReadController<DtrDay> {
    private final DtrDayRepository days;
    public DtrDayController(DtrDayRepository repo, ObjectMapper mapper) {
        super(repo);
        this.days = repo;
    }

    /** Recent DTR rows requiring attendance review before payroll lock. */
    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam(required = false) LocalDate from,
                                                @RequestParam(required = false) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(366))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exception date range must be within the last 366 days");
        }
        return days.findByWorkDateBetweenOrderByWorkDateDesc(start,end).stream()
                .filter(d -> (d.getFirstIn() == null) != (d.getLastOut() == null)
                        || (Boolean.TRUE.equals(d.getAbsent()) && !Boolean.TRUE.equals(d.getPaidLeave())))
                .map(d -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", d.getId()); row.put("employeeId", d.getEmployeeId());
                    row.put("workDate", d.getWorkDate()); row.put("status", d.getStatus());
                    row.put("firstIn", d.getFirstIn()); row.put("lastOut", d.getLastOut());
                    row.put("reason", (d.getFirstIn() == null) != (d.getLastOut() == null)
                            ? "Incomplete clock-in/clock-out pair" : "Absent DTR requires review");
                    return row;
                }).toList();
    }
}
