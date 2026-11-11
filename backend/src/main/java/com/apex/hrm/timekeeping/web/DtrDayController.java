package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.domain.WorkSchedule;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.apex.hrm.timekeeping.repo.TimeLogRepository;
import com.apex.hrm.timekeeping.repo.WorkScheduleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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
    private final TimeLogRepository timeLogs;
    private final WorkScheduleRepository schedules;
    private final ZoneId zone;
    public DtrDayController(DtrDayRepository repo, ObjectMapper mapper, TimeLogRepository timeLogs,
                            WorkScheduleRepository schedules, @Value("${hrm.timezone:Asia/Manila}") String timezone) {
        super(repo);
        this.days = repo;
        this.timeLogs = timeLogs;
        this.schedules = schedules;
        this.zone = ZoneId.of(timezone);
    }

    /** Recent DTR rows requiring attendance review before payroll lock. */
    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam(required = false) LocalDate from,
                                                @RequestParam(required = false) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(90))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exception date range must be within the last 90 days");
        }
        Instant punchRangeStart=start.minusDays(1).atStartOfDay(zone).toInstant();
        Instant punchRangeEnd=end.plusDays(2).atStartOfDay(zone).toInstant();
        Map<UUID,List<TimeLog>> punchesByEmployee=timeLogs.findByLogTimeBetweenAndStatusOrderByLogTime(
                punchRangeStart,punchRangeEnd,"VALID").stream().collect(Collectors.groupingBy(TimeLog::getEmployeeId));
        Map<UUID,WorkSchedule> scheduleCache=new HashMap<>();
        List<Map<String,Object>> result = new ArrayList<>();
        for (DtrDay d : days.findByWorkDateBetweenOrderByWorkDateDesc(start,end)) {
            List<String> reasons = new ArrayList<>();
            List<TimeLog> punches = List.of();
            WorkSchedule schedule = d.getScheduleId() == null ? null : scheduleCache.computeIfAbsent(d.getScheduleId(),id->schedules.findById(id).orElse(null));
            if (schedule != null) {
                Instant shiftStart = d.getWorkDate().atTime(schedule.getStartTime()).atZone(zone).toInstant();
                Instant shiftEnd = d.getWorkDate().atTime(schedule.getEndTime()).atZone(zone).toInstant();
                if (!shiftEnd.isAfter(shiftStart)) shiftEnd = d.getWorkDate().plusDays(1).atTime(schedule.getEndTime()).atZone(zone).toInstant();
                Instant windowStart=shiftStart.minus(Duration.ofHours(4));
                Instant windowEnd=shiftEnd.plus(Duration.ofHours(6));
                punches = punchesByEmployee.getOrDefault(d.getEmployeeId(),List.of()).stream()
                        .filter(p->!p.getLogTime().isBefore(windowStart)&&!p.getLogTime().isAfter(windowEnd)).toList();
                String sequenceIssue = sequenceIssue(punches);
                if (sequenceIssue != null) reasons.add(sequenceIssue);
                int recordedBreakMinutes = recordedBreakMinutes(punches);
                if (recordedBreakMinutes >= 0 && schedule.getBreakMinutes() != null
                        && Math.abs(recordedBreakMinutes-schedule.getBreakMinutes()) > 5) {
                    reasons.add("Recorded break time ("+recordedBreakMinutes+"m) differs from scheduled break ("+schedule.getBreakMinutes()+"m)");
                }
            }
            if ((d.getFirstIn() == null) != (d.getLastOut() == null)) reasons.add("Incomplete clock-in/clock-out pair");
            if (Boolean.TRUE.equals(d.getAbsent()) && !Boolean.TRUE.equals(d.getPaidLeave())) reasons.add("Absent DTR requires review");
            if (reasons.isEmpty()) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id",d.getId()); row.put("employeeId",d.getEmployeeId()); row.put("workDate",d.getWorkDate());
            row.put("status",d.getStatus()); row.put("firstIn",d.getFirstIn()); row.put("lastOut",d.getLastOut());
            row.put("reason",String.join("; ",reasons)); row.put("rawPunchCount",punches.size());
            row.put("recordedBreakMinutes",recordedBreakMinutes(punches));
            row.put("scheduledBreakMinutes",schedule==null?null:schedule.getBreakMinutes());
            result.add(row);
        }
        return result;
    }

    private static String sequenceIssue(List<TimeLog> punches) {
        if (punches.size()<2) return null;
        String expected="IN";
        for(TimeLog punch:punches) {
            String actual=punch.getLogType()==null?"":punch.getLogType().trim().toUpperCase(Locale.ROOT);
            if(!actual.equals(expected)) return "Punch sequence has an unpaired or duplicate "+actual+" entry";
            expected="IN".equals(expected)?"OUT":"IN";
        }
        if(!"IN".equals(expected)) return "Punch sequence ends without a matching clock-out";
        return punches.size()>2?"Multiple punch pairs detected; review intervening work and break periods":null;
    }

    /** Sum internal OUT-to-next-IN intervals; -1 means the punch sequence is not pairable. */
    private static int recordedBreakMinutes(List<TimeLog> punches) {
        if(punches.size()<4||punches.size()%2!=0) return -1;
        int total=0;
        for(int i=1;i<punches.size()-1;i+=2) {
            if(!"OUT".equalsIgnoreCase(punches.get(i).getLogType())||!"IN".equalsIgnoreCase(punches.get(i+1).getLogType())) return -1;
            total+=Math.max(0,(int)Duration.between(punches.get(i).getLogTime(),punches.get(i+1).getLogTime()).toMinutes());
        }
        return total;
    }
}
