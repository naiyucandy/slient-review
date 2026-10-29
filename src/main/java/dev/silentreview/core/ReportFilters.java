package dev.silentreview.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters stored reports without changing their result, resolution or finding indices. */
public final class ReportFilters {
    private ReportFilters() {}
    /** roots 为 null 表示全部仓库；空集合表示不显示任何仓库。 */
    public static List<Review.Report> select(List<Review.Report> reports,Map<String,Reports.Resolution> resolutions,
                                            Set<String> roots,Reports.Decision decision,String severity) {
        return reports.stream().filter(r->roots==null||roots.contains(r.root()))
            .filter(r->decision==null||Reports.resolution(r.id(),resolutions).decision()==decision)
            .filter(r->severity==null||r.result().findings().stream().anyMatch(f->severity.equals(f.severity())))
            .toList();
    }
}
