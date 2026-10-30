package dev.silentreview.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReportPresentationTest {
    @Test void countsFindingsAndDistinctFilesWithoutTreatingReviewedAsPassed() {
        var result = new Review.Result(List.of(
            finding("src/A.java", "error", "violation", 5),
            finding("src/A.java", "warning", "needs_confirmation", 8),
            finding("src/B.java", "info", "suggestion", 2),
            finding("src/B.java", "custom", "suggestion", 3)
        ), List.of(
            check("src/A.java", "reviewed"),
            check("src/B.java", "reviewed"),
            check("src/C.java", "needs_context")
        ), List.of("跨文件上下文不足"), Review.Status.PARTIAL);

        var summary = ReportPresentation.summarize(result);
        assertEquals(4, summary.findings());
        assertEquals(3, summary.files());
        assertEquals(1, summary.errors());
        assertEquals(1, summary.warnings());
        assertEquals(1, summary.infos());
        assertEquals(1, summary.otherSeverities());
        assertEquals(2, summary.reviewedChecks());
        assertEquals(1, summary.incompleteChecks());
        assertEquals(1, summary.limitations());
        assertEquals(1, summary.needsConfirmationFindings());
        assertTrue(summary.needsConfirmation());
        assertEquals("已检查", ReportPresentation.coverageLabel("reviewed"));
    }

    @Test void everyNonReviewedCoverageStateRemainsIncompleteIncludingUnknownPassed() {
        var result = new Review.Result(List.of(), Stream.of("reviewed", "needs_context", "skipped", "failed", "passed", "future_state")
            .map(status -> check("src/A.java", status)).toList(), List.of(), Review.Status.COMPLETED);

        var summary = ReportPresentation.summarize(result);
        assertEquals(1, summary.reviewedChecks());
        assertEquals(5, summary.incompleteChecks());
        assertTrue(summary.needsConfirmation());
        assertEquals("缺少上下文", ReportPresentation.coverageLabel("needs_context"));
        assertEquals("已跳过", ReportPresentation.coverageLabel("skipped"));
        assertEquals("检查失败", ReportPresentation.coverageLabel("failed"));
        assertEquals("passed", ReportPresentation.coverageLabel("passed"));
        assertEquals("future_state", ReportPresentation.coverageLabel("future_state"));
    }

    @Test void everyIncompleteExecutionStatusRemainsVisibleWithNoFindings() {
        for (Review.Status status : Review.Status.values()) {
            var result = new Review.Result(List.of(), List.of(), List.of(), status);
            assertEquals(status != Review.Status.COMPLETED,
                ReportPresentation.summarize(result).needsConfirmation(), status.toString());
        }
    }

    @Test void confirmationFindingsAndLimitationsAreNotHiddenByCompletedStatus() {
        var pending = new Review.Result(List.of(finding("A.java", "info", "needs_confirmation", 1)),
            List.of(check("A.java", "reviewed")), List.of(), Review.Status.COMPLETED);
        assertTrue(ReportPresentation.summarize(pending).needsConfirmation());
        assertEquals(0, ReportPresentation.summarize(pending).incompleteChecks());
        var limited = new Review.Result(List.of(), List.of(check("A.java", "reviewed")),
            List.of("仅检查部分文件"), Review.Status.COMPLETED);
        assertTrue(ReportPresentation.summarize(limited).needsConfirmation());
    }

    @Test void sortsFileGroupsAndFindingsWhilePreservingOriginalNavigationIndices() {
        List<Review.Finding> originals = List.of(
            finding("z.java", "info", "suggestion", 2),
            finding("b.java", "warning", "violation", 1),
            finding("a.java", "error", "violation", 20),
            finding("z.java", "error", "violation", 8),
            finding("a.java", "error", "violation", 4),
            finding("a.java", "warning", "violation", 1),
            finding("a.java", "error", "violation", 4),
            finding("first.java", "custom", "custom", 1)
        );
        var groups = ReportPresentation.groups(new Review.Result(originals, List.of(), List.of(), Review.Status.COMPLETED));

        assertEquals(List.of("a.java", "z.java", "b.java", "first.java"), groups.stream().map(ReportPresentation.FileGroup::filePath).toList());
        assertEquals(List.of(4, 6, 2, 5), groups.get(0).findings().stream().map(ReportPresentation.IndexedFinding::index).toList());
        assertEquals(List.of(3, 0), groups.get(1).findings().stream().map(ReportPresentation.IndexedFinding::index).toList());
        for (var group : groups) for (var item : group.findings()) assertSame(originals.get(item.index()), item.finding());
        assertEquals("z.java", originals.get(0).filePath());
        assertThrows(UnsupportedOperationException.class, () -> groups.clear());
        assertThrows(UnsupportedOperationException.class, () -> groups.get(0).findings().clear());
    }

    @Test void knownLabelsAreChineseAndUnknownLabelsRetainTheirOriginalValue() {
        assertEquals("错误", ReportPresentation.severityLabel("error"));
        assertEquals("警告", ReportPresentation.severityLabel("warning"));
        assertEquals("提示", ReportPresentation.severityLabel("info"));
        assertEquals("规范问题", ReportPresentation.kindLabel("violation"));
        assertEquals("改进建议", ReportPresentation.kindLabel("suggestion"));
        assertEquals("待人工确认", ReportPresentation.kindLabel("needs_confirmation"));
        assertEquals("CUSTOM Severity", ReportPresentation.severityLabel("CUSTOM Severity"));
        assertEquals("CUSTOM Kind", ReportPresentation.kindLabel("CUSTOM Kind"));
        assertEquals("CUSTOM Coverage", ReportPresentation.coverageLabel("CUSTOM Coverage"));
    }

    @Test void compactTitlesUseTheFirstNonBlankLineAndKeepUnicodeCharactersIntact() {
        assertEquals("首行说明", ReportPresentation.firstLine("\r\n  首行说明  \r\n完整说明保留在详情中", 60));
        assertEquals("鲸🐳…", ReportPresentation.firstLine("鲸🐳鱼说明", 3));
        assertEquals("…", ReportPresentation.firstLine("非常长", 1));
        assertEquals("", ReportPresentation.firstLine("\n  \n", 60));
        assertEquals("", ReportPresentation.firstLine(null));
        assertThrows(IllegalArgumentException.class, () -> ReportPresentation.firstLine("说明", 0));
    }

    @Test void conclusionDistinguishesCompletePartialFailedCanceledAndNoApplicableChecks() {
        var clean = new Review.Result(List.of(), List.of(check("A.java", "reviewed")), List.of(), Review.Status.COMPLETED);
        assertEquals("本次约定范围内的检查未发现问题。", ReportPresentation.conclusion(clean));
        var withProblem = new Review.Result(List.of(finding("A.java", "error", "violation", 1)),
            clean.coverage(), List.of(), Review.Status.COMPLETED);
        assertEquals("本次检查已完成，共发现 1 项问题。", ReportPresentation.conclusion(withProblem));
        assertEquals("审查仅部分完成，当前未发现问题不代表全部检查完成。", ReportPresentation.conclusion(
            new Review.Result(List.of(), clean.coverage(), List.of(), Review.Status.PARTIAL)));
        assertTrue(ReportPresentation.conclusion(new Review.Result(withProblem.findings(), clean.coverage(), List.of(), Review.Status.PARTIAL))
            .contains("仅部分完成，已发现 1 项问题"));
        assertTrue(ReportPresentation.conclusion(new Review.Result(List.of(), List.of(), List.of(), Review.Status.FAILED)).contains("执行失败"));
        assertTrue(ReportPresentation.conclusion(new Review.Result(List.of(), List.of(), List.of(), Review.Status.CANCELED)).contains("已取消"));
        assertEquals("本次范围内没有适用的检查规则。", ReportPresentation.conclusion(
            new Review.Result(List.of(), List.of(), List.of(), Review.Status.NOT_APPLICABLE)));
        assertTrue(ReportPresentation.conclusion(new Review.Result(List.of(), List.of(check("A.java", "needs_context")), List.of(), Review.Status.COMPLETED))
            .contains("仍有未完成或待确认"));
    }

    private static Review.Finding finding(String path, String severity, String kind, int line) {
        return new Review.Finding("RULE", kind, severity, path, "after", line, line,
            "source()", "完整说明", "修复建议", "新增代码触发规则");
    }

    private static Review.Coverage check(String path, String status) {
        return new Review.Coverage(path, "RULE", status, "");
    }
}
