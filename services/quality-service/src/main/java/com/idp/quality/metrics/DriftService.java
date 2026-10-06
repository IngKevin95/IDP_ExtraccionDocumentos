package com.idp.quality.metrics;

import com.idp.quality.config.QualityProperties;
import com.idp.quality.metrics.DriftDetector.Day;
import com.idp.quality.metrics.DriftDetector.Verdict;
import com.idp.quality.metrics.MetricsRepository.DailyRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Evalua deriva por tenant y dia (todas las tipologias) y persiste las alertas para el dashboard. */
@Service
public class DriftService {

    private static final Logger LOG = LoggerFactory.getLogger(DriftService.class);
    static final String KIND_SILENT = "SILENT_ERROR";
    static final String KIND_STP_DROP = "STP_DROP";

    private final MetricsRepository repo;
    private final DriftDetector detector;
    private final int baselineDays;

    public DriftService(MetricsRepository repo, QualityProperties props) {
        this.repo = repo;
        QualityProperties.Drift d = props.drift();
        this.detector = new DriftDetector(d.silentErrorMax(), d.stpDropPoints(), d.minSamples());
        this.baselineDays = d.baselineDays();
    }

    /** AC-08: veredicto de deriva del dia frente a los {@code baselineDays} anteriores. */
    public Verdict evaluate(UUID tenant, LocalDate day) {
        List<DailyRow> window = repo.daily(tenant, day.minusDays(baselineDays), day, null);
        Day today = new Day(0, 0, 0, 0);
        java.util.ArrayList<Day> history = new java.util.ArrayList<>();
        for (DailyRow r : window) {
            Day d = new Day(r.total(), r.stp(), r.blindSamples(), r.silentErrors());
            if (r.fecha().equals(day)) {
                today = d;
            } else {
                history.add(d);
            }
        }
        return detector.evaluate(today, history);
    }

    /** Job diario: evalua el dia indicado para todos los tenants con metricas y persiste las alertas. Devuelve cuantas. */
    public int evaluateAll(LocalDate day) {
        int alerts = 0;
        for (UUID tenant : repo.tenantsWithMetrics(day, day)) {
            Verdict v = evaluate(tenant, day);
            if (v.silentErrorDrift()) {
                repo.saveDriftAlert(tenant, day, KIND_SILENT, v.silentRate(), 0.0);
                alerts++;
            }
            if (v.stpDrop()) {
                repo.saveDriftAlert(tenant, day, KIND_STP_DROP, v.stpRate(), v.stpBaseline());
                alerts++;
            }
            if (v.drift()) {
                LOG.warn("Deriva de calidad detectada tenant={} fecha={} silentRate={} stpRate={} stpBaseline={}",
                    tenant, day, v.silentRate(), v.stpRate(), v.stpBaseline());
            }
        }
        return alerts;
    }
}
