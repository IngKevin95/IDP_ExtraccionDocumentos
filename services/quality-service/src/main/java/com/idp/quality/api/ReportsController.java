package com.idp.quality.api;

import com.idp.quality.api.CallerResolver.Caller;
import com.idp.quality.metrics.ReportService;
import com.idp.quality.metrics.Reports;
import com.idp.security.Roles;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes de calidad del tenant del token (STP, error silente, campos, costo y latencia). */
@RestController
@RequestMapping("/v1/quality/reports")
public class ReportsController {

    static final Set<String> TIPOLOGIAS = Set.of("EC", "EJ", "DC", "DJ");
    private static final long MAX_RANGE_DAYS = 366;

    private final ReportService reports;
    private final CallerResolver callers;

    public ReportsController(ReportService reports, CallerResolver callers) {
        this.reports = reports;
        this.callers = callers;
    }

    @GetMapping("/stp")
    public Reports.StpReport stp(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam("start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                 LocalDate start,
                                 @RequestParam("end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                 LocalDate end,
                                 @RequestParam(value = "tipologia", required = false) String tipologia) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD, Roles.RIESGO_MODELO);
        range(start, end);
        return reports.stp(c.tenantId(), start, end, tipologia(tipologia));
    }

    @GetMapping("/silent-error")
    public Reports.SilentErrorReport silentError(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam("start_date") @DateTimeFormat(
                                                     iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                                 @RequestParam("end_date") @DateTimeFormat(
                                                     iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD, Roles.RIESGO_MODELO);
        range(start, end);
        return reports.silentError(c.tenantId(), start, end);
    }

    @GetMapping("/fields")
    public Reports.FieldReport fields(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam("start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate start,
                                      @RequestParam("end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate end,
                                      @RequestParam(value = "tipologia", required = false) String tipologia) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD, Roles.RIESGO_MODELO);
        range(start, end);
        return reports.fields(c.tenantId(), start, end, tipologia(tipologia));
    }

    @GetMapping("/performance")
    public Reports.PerformanceReport performance(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam("start_date") @DateTimeFormat(
                                                     iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                                 @RequestParam("end_date") @DateTimeFormat(
                                                     iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                                 @RequestParam(value = "tipologia", required = false)
                                                 String tipologia) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD, Roles.RIESGO_MODELO);
        range(start, end);
        return reports.performance(c.tenantId(), start, end, tipologia(tipologia));
    }

    static void range(LocalDate start, LocalDate end) {
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Rango de fechas invalido");
        }
    }

    static String tipologia(String t) {
        if (t != null && !TIPOLOGIAS.contains(t)) {
            throw new IllegalArgumentException("Tipologia invalida");
        }
        return t;
    }
}
