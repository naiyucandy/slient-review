package dev.silentreview.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters stored reports without changing their result, resolution or finding indices. */
public final class ReportFilters {
    private ReportFilters() {}
    public static List<Review.Report> select(List<Review.Report> reports,Map<String,Reports.Resolution> resolutions,
                                            String root,Reports.Decision decision,String severity) {
        return reports.stream().filter(r->root==null||root.equals(r.root()))
            .filter(r->decision==null||Reports.resolution(r.id(),resolutions).decision()==decision)
            .filter(r->severity==null||r.result().findings().stream().anyMatch(f->severity.equals(f.severity())))
            .toList();
    }
    /** Two display groups without changing the original four-state audit records. */
    public static List<Review.Report> selectForDisplay(List<Review.Report> reports,Map<String,Reports.Resolution> resolutions,
                                                      Set<String> roots,Reports.Decision decision,String severity) {
        Set<String> selected=roots==null?null:Set.copyOf(roots);
        return reports.stream().filter(report->selected==null||selected.contains(report.root()))
            .filter(report->{
                Reports.Decision stored=Reports.resolution(report.id(),resolutions).decision();
                return decision==null||(decision==Reports.Decision.CONFIRMED
                    ?stored==Reports.Decision.CONFIRMED||stored==Reports.Decision.ACCEPTED||stored==Reports.Decision.CLOSED
                    :stored==decision);
            })
            .filter(report->severity==null||report.result().findings().stream().anyMatch(finding->severity.equals(finding.severity())))
            .toList();
    }
}
