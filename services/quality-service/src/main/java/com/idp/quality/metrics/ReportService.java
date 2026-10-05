package com.idp.quality.metrics;

import com.idp.quality.config.QualityProperties;
import com.idp.quality.golden.EvaluationResult;
import com.idp.quality.golden.GoldenRepository;
import com.idp.quality.metrics.MetricsRepository.DailyRow;
import com.idp.quality.metrics.MetricsRepository.FieldErrorRow;
import com.idp.quality.metrics.Reports.FieldReport;
import com.idp.quality.metrics.Reports.FieldRow;
import com.idp.quality.metrics.Reports.PerformanceReport;
import com.idp.quality.metrics.Reports.SilentErrorDay;
import com.idp.quality.metrics.Reports.SilentErrorReport;
import com.idp.quality.metrics.Reports.StpDay;
import com.idp.quality.metrics.Reports.StpReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Consultas de solo lectura sobre los agregados de calidad del tenant del llamador. */
@Service
public class ReportService {

    private final MetricsRepository metrics;
    private final GoldenRepository golden;
    private final DriftService drift;
    private final QualityProperties props;

    public ReportService(MetricsRepository metrics, GoldenRepository golden, DriftService drift,
                         QualityProperties props) {
        this.metrics = metrics;
        this.golden = golden;
        this.drift = drift;
        this.props = props;
    }

    public StpReport stp(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        List<StpDay> data = new ArrayList<>();
        for (DailyRow d : metrics.daily(tenant, from, to, tipologia)) {
            data.add(new StpDay(d.fecha(), d.total(), d.stp(), d.hitl(), pct(d.stp(), d.total()),
                pct(d.hitl(), d.total())));
        }
        return new StpReport(tenant, data);
    }

    public SilentErrorReport silentError(UUID tenant, LocalDate from, LocalDate to) {
        List<SilentErrorDay> data = new ArrayList<>();
        for (DailyRow d : metrics.daily(tenant, from, to, null)) {
            boolean flagged = drift.evaluate(tenant, d.fecha()).drift();
            data.add(new SilentErrorDay(d.fecha(), d.blindSamples(), d.silentErrors(),
                pct(d.silentErrors(), d.blindSamples()), flagged));
        }
        return new SilentErrorReport(tenant, data);
    }

    /** Tasa de correccion humana por campo (correcciones HITL / revisiones HITL del rango) y precision golden. */
    public FieldReport fields(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        Map<String, Integer> hitlByTipologia = new LinkedHashMap<>();
        for (String t : tipologias(tenant, from, to, tipologia)) {
            hitlByTipologia.put(t, metrics.daily(tenant, from, to, t).stream().mapToInt(DailyRow::hitl).sum());
        }
        EvaluationResult latest = props.currentModelPrompt().isBlank() ? null
            : golden.latestResult(tenant, props.currentModelPrompt()).orElse(null);
        Map<String, int[]> byField = new LinkedHashMap<>(); // {hitl, blind}
        for (FieldErrorRow r : metrics.fieldErrors(tenant, from, to, tipologia)) {
            int[] c = byField.computeIfAbsent(r.tipologia() + "." + r.campo(), k -> new int[2]);
            c["BLIND".equals(r.origen()) ? 1 : 0] += r.cuenta();
        }
        List<FieldRow> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byField.entrySet()) {
            String tip = e.getKey().substring(0, e.getKey().indexOf('.'));
            String campo = e.getKey().substring(e.getKey().indexOf('.') + 1);
            int hitl = hitlByTipologia.getOrDefault(tip, 0);
            Double rate = hitl == 0 ? null : (double) e.getValue()[0] / hitl;
            EvaluationResult.FieldMetric fm = latest == null ? null : latest.fields().stream()
                .filter(f -> f.tipologia().equals(tip) && f.campo().equals(campo)).findFirst().orElse(null);
            rows.add(new FieldRow(tip, campo, e.getValue()[0], e.getValue()[1], rate,
                fm == null ? null : fm.precision(), fm == null ? null : fm.recall()));
        }
        return new FieldReport(tenant, rows);
    }

    public PerformanceReport performance(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        long[] lat = metrics.latencies(tenant, from, to, tipologia);
        long[] cost = metrics.costs(tenant, from, to, tipologia);
        int min = props.performance().minSample();
        Long p95Lat = Percentile.nearestRank(lat, 95, min).isPresent()
            ? Percentile.nearestRank(lat, 95, min).getAsLong() : null;
        Long p95Cost = Percentile.nearestRank(cost, 95, min).isPresent()
            ? Percentile.nearestRank(cost, 95, min).getAsLong() : null;
        Double avgCost = cost.length >= min && cost.length > 0
            ? java.util.Arrays.stream(cost).average().orElse(0) : null;
        return new PerformanceReport(tenant, lat.length, p95Lat, cost.length, p95Cost, avgCost, min,
            p95Lat != null || p95Cost != null);
    }

    private List<String> tipologias(UUID tenant, LocalDate from, LocalDate to, String tipologia) {
        if (tipologia != null) {
            return List.of(tipologia);
        }
        return metrics.fieldErrors(tenant, from, to, null).stream().map(FieldErrorRow::tipologia).distinct()
            .toList();
    }

    static double pct(int num, int den) {
        return den == 0 ? 0.0 : Math.round(num * 10000.0 / den) / 100.0;
    }
}
