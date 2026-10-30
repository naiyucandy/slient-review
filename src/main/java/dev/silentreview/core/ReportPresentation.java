package dev.silentreview.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only display data shared by the report panel and exported reports. */
public final class ReportPresentation {
    private ReportPresentation() {}

    public record Summary(int findings, int files, int errors, int warnings, int infos,
                          int otherSeverities, int reviewedChecks, int incompleteChecks,
                          int limitations, int needsConfirmationFindings, boolean needsConfirmation) {}

    /** index always addresses the finding in the original report, even after display sorting. */
    public record IndexedFinding(int index, Review.Finding finding) {
        public IndexedFinding {
            if (index < 0) throw new IllegalArgumentException("Finding index must not be negative");
            Objects.requireNonNull(finding, "finding");
        }
    }

    public record FileGroup(String filePath, List<IndexedFinding> findings) {
        public FileGroup { findings = List.copyOf(findings); }
    }

    public static Summary summarize(Review.Report report) {
        return summarize(Objects.requireNonNull(report, "report").result());
    }

    public static Summary summarize(Review.Result result) {
        Objects.requireNonNull(result, "result");
        int total = 0, errors = 0, warnings = 0, infos = 0, others = 0, confirmations = 0;
        Set<String> files = new HashSet<>();
        for (Review.Finding finding : findings(result)) {
            if (finding == null) continue;
            total++;
            files.add(text(finding.filePath()));
            switch (text(finding.severity())) {
                case "error" -> errors++;
                case "warning" -> warnings++;
                case "info" -> infos++;
                default -> others++;
            }
            if ("needs_confirmation".equals(finding.kind())) confirmations++;
        }
        int reviewed = 0, incomplete = 0;
        for (Review.Coverage check : coverage(result)) {
            if (check != null) files.add(text(check.filePath()));
            // Reviewed describes execution coverage, not whether the code passed its rules.
            // Unknown states must stay incomplete rather than silently counting as success.
            if (check != null && "reviewed".equals(check.status())) reviewed++;
            else incomplete++;
        }
        files.remove("");
        int limits = result.limitations() == null ? 0 : result.limitations().size();
        boolean needsConfirmation = result.status() != Review.Status.COMPLETED
            || incomplete > 0 || confirmations > 0 || limits > 0;
        return new Summary(total, files.size(), errors, warnings, infos, others, reviewed,
            incomplete, limits, confirmations, needsConfirmation);
    }

    public static String conclusion(Review.Report report) {
        return conclusion(Objects.requireNonNull(report, "report").result());
    }

    public static String conclusion(Review.Result result) {
        Summary summary = summarize(result);
        if (result.status() == null) return "审查状态未知，请核对检查覆盖和限制说明。";
        return switch (result.status()) {
            case COMPLETED -> {
                if (summary.needsConfirmation()) yield "审查仍有未完成或待确认的内容，请核对检查覆盖和限制说明。";
                if (summary.findings() == 0) yield "本次约定范围内的检查未发现问题。";
                yield "本次检查已完成，共发现 " + summary.findings() + " 项问题。";
            }
            case PARTIAL -> summary.findings() == 0
                ? "审查仅部分完成，当前未发现问题不代表全部检查完成。"
                : "审查仅部分完成，已发现 " + summary.findings() + " 项问题；未完成或待确认的内容仍需处理。";
            case FAILED -> "审查执行失败，未完成本次约定范围内的检查。";
            case CANCELED -> "审查已取消，未完成本次约定范围内的检查。";
            case NOT_APPLICABLE -> "本次范围内没有适用的检查规则。";
            case QUEUED -> "审查正在等待执行，尚未完成检查。";
            case PREPARING -> "审查正在准备，尚未完成检查。";
            case RUNNING -> "审查正在执行，尚未完成检查。";
        };
    }

    public static List<FileGroup> groups(Review.Report report) {
        return groups(Objects.requireNonNull(report, "report").result());
    }

    /** Groups the original findings without modifying their order or their navigation indices. */
    public static List<FileGroup> groups(Review.Result result) {
        Objects.requireNonNull(result, "result");
        Map<String, List<IndexedFinding>> byFile = new LinkedHashMap<>();
        List<Review.Finding> original = findings(result);
        for (int i = 0; i < original.size(); i++) {
            Review.Finding finding = original.get(i);
            if (finding != null) byFile.computeIfAbsent(text(finding.filePath()), path -> new ArrayList<>())
                .add(new IndexedFinding(i, finding));
        }
        Comparator<IndexedFinding> findingOrder = Comparator
            .comparingInt((IndexedFinding item) -> severityOrder(item.finding().severity()))
            .thenComparingInt(item -> item.finding().startLine())
            .thenComparingInt(IndexedFinding::index);
        List<FileGroup> groups = new ArrayList<>();
        byFile.forEach((path, items) -> {
            items.sort(findingOrder);
            groups.add(new FileGroup(path, items));
        });
        groups.sort(Comparator.comparingInt((FileGroup group) -> severityOrder(group.findings().get(0).finding().severity()))
            .thenComparing(FileGroup::filePath, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(FileGroup::filePath));
        return List.copyOf(groups);
    }

    public static String severityLabel(String severity) {
        return switch (text(severity)) {
            case "error" -> "错误";
            case "warning" -> "警告";
            case "info" -> "提示";
            default -> text(severity);
        };
    }

    public static String kindLabel(String kind) {
        return switch (text(kind)) {
            case "violation" -> "规范问题";
            case "suggestion" -> "改进建议";
            case "needs_confirmation" -> "待人工确认";
            default -> text(kind);
        };
    }

    public static String coverageLabel(String status) {
        return switch (text(status)) {
            case "reviewed" -> "已检查";
            case "needs_context" -> "缺少上下文";
            case "skipped" -> "已跳过";
            case "failed" -> "检查失败";
            default -> text(status);
        };
    }

    public static String firstLine(String value) { return firstLine(value, 80); }

    /** A compact title, without cutting a Unicode code point or discarding the full source text. */
    public static String firstLine(String value, int maxCodePoints) {
        if (maxCodePoints < 1) throw new IllegalArgumentException("Title length must be positive");
        String line = text(value).lines().map(String::strip).filter(item -> !item.isEmpty()).findFirst().orElse("");
        if (line.codePointCount(0, line.length()) <= maxCodePoints) return line;
        return line.substring(0, line.offsetByCodePoints(0, maxCodePoints - 1)) + "…";
    }

    private static int severityOrder(String severity) {
        return switch (text(severity)) {
            case "error" -> 0;
            case "warning" -> 1;
            case "info" -> 2;
            default -> 3;
        };
    }

    private static List<Review.Finding> findings(Review.Result result) {
        return result.findings() == null ? List.of() : result.findings();
    }

    private static List<Review.Coverage> coverage(Review.Result result) {
        return result.coverage() == null ? List.of() : result.coverage();
    }

    private static String text(String value) { return value == null ? "" : value; }
}
