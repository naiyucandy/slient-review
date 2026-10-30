package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FindingMarkdownTest {
    @TempDir Path directory;

    @Test void displayedFileSortingKeepsConfirmationAttachedToTheOriginalFinding()throws Exception {
        var report=report(Review.Status.COMPLETED);Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),0,true,"已修复 A","alice");
        String markdown=export(store,report);
        assertTrue(markdown.contains("问题处理：待确认 1 项，已确认解决 1 项"));
        int errorFile=markdown.indexOf("### src/Z"),warningFile=markdown.indexOf("### src/A");
        assertTrue(errorFile>0&&warningFile>errorFile);
        String errorSection=markdown.substring(errorFile,warningFile);
        String warningSection=markdown.substring(warningFile,markdown.indexOf("## 检查覆盖"));
        assertTrue(errorSection.contains("**解决确认：** 待确认"));assertFalse(errorSection.contains("已确认解决"));
        assertTrue(warningSection.contains("**解决确认：** 已确认解决"));assertTrue(warningSection.contains("已修复 A"));
        assertTrue(warningSection.contains("alice"));assertTrue(warningSection.contains("第 2 行"));
        assertTrue(errorSection.contains("第 8 行"));
    }

    @Test void exportIncludesEveryConfirmationAndReopenEventAndEscapesAuditText()throws Exception {
        var report=report(Review.Status.COMPLETED);Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),0,true,"首次修复","alice");
        store.acknowledgeFinding(report.id(),0,false,"需要重新检查","bob");
        var latest=store.acknowledgeFinding(report.id(),0,true,"<script>note</script> | 下一步\n完成","carol");
        String markdown=export(new Reports(directory),report);
        assertEquals(3,latest.history().size());
        assertTrue(markdown.contains("问题处理记录（3 条）"));assertTrue(markdown.contains("恢复待确认"));
        for(String text:List.of("首次修复","需要重新检查","alice","bob","carol","更新时间：","操作者："))assertTrue(markdown.contains(text),text);
        assertFalse(markdown.contains("<script>note</script>"));
        assertTrue(markdown.contains("&lt;script&gt;note&lt;/script&gt; \\| 下一步<br>完成"));
        assertTrue(markdown.contains("**解决确认：** 已确认解决"));
    }

    @Test void mismatchedReportIndexOrEvidenceKeyCannotExportAResolvedState()throws Exception {
        var report=report(Review.Status.COMPLETED);Reports store=new Reports(directory);store.save(report);
        var saved=store.acknowledgeFinding(report.id(),0,true,"valid note","alice");
        var foreignReport=new Reports.FindingResolution(UUID.randomUUID().toString(),0,saved.findingKey(),true,saved.updatedAt(),"foreign","foreign note",saved.history());
        var foreignKey=new Reports.FindingResolution(report.id(),0,"0".repeat(64),true,saved.updatedAt(),"foreign","foreign note",saved.history());
        var foreignIndex=new Reports.FindingResolution(report.id(),1,saved.findingKey(),true,saved.updatedAt(),"foreign","foreign note",saved.history());
        for(var invalid:List.of(foreignReport,foreignKey,foreignIndex)) {
            String markdown=Reports.markdown(report,null,Map.of(0,invalid,999,saved));
            assertTrue(markdown.contains("问题处理：待确认 2 项，已确认解决 0 项"));
            assertFalse(markdown.contains("**解决确认：** 已确认解决"));
            assertFalse(markdown.contains("foreign"));assertFalse(markdown.contains("valid note"));
        }
    }

    @Test void confirmingEveryFindingDoesNotCompletePartialOrFailedAiChecks()throws Exception {
        for(Review.Status status:List.of(Review.Status.PARTIAL,Review.Status.FAILED)) {
            var report=report(status);Reports store=new Reports(directory.resolve(status.name()));store.save(report);
            store.acknowledgeFinding(report.id(),0,true,"人工修复 A","alice");
            store.acknowledgeFinding(report.id(),1,true,"人工修复 Z","alice");
            String markdown=export(store,report);
            assertTrue(markdown.contains("问题处理：待确认 0 项，已确认解决 2 项"));
            assertTrue(markdown.contains("执行状态："+Reports.label(status)));
            assertFalse(markdown.contains("本次约定范围内的检查未发现问题"));
            assertTrue(markdown.contains("待补查 1 项"));assertTrue(markdown.contains("上下文仍需补查"));
            assertTrue(markdown.contains("不代表 AI 已重新检查"));assertTrue(markdown.contains("| 错误 | 1 |"));
        }
    }

    @Test void reportLevelAcceptanceAndLegacyOverloadsLeaveEachFindingPending()throws Exception {
        var report=report(Review.Status.COMPLETED);Reports store=new Reports(directory);store.save(report);
        var resolution=store.acknowledge(report.id(),Reports.Decision.ACCEPTED,"已采纳报告","alice");
        for(String markdown:List.of(Reports.markdown(report),Reports.markdown(report,resolution),Reports.markdown(report,resolution,null))) {
            assertTrue(markdown.contains("问题处理：待确认 2 项，已确认解决 0 项"));
            assertFalse(markdown.contains("**解决确认：** 已确认解决"));
        }
    }

    private static String export(Reports store,Review.Report report)throws Exception {
        return Reports.markdown(report,Reports.resolution(report.id(),store.loadResolutions()),
            store.loadFindingResolutions().getOrDefault(report.id(),Map.of()));
    }

    private static Review.Report report(Review.Status status) {
        // Original index 0 sorts after index 1 in the presentation.
        var findings=List.of(
            new Review.Finding("JAVA-001","violation","warning","src/A","after",2,2,"evidence A","原因 A","建议 A","新增 A"),
            new Review.Finding("JAVA-001","violation","error","src/Z","after",8,8,"evidence Z","原因 Z","建议 Z","新增 Z"));
        boolean incomplete=status!=Review.Status.COMPLETED;
        var coverage=List.of(new Review.Coverage("src/A","JAVA-001","reviewed",""),
            new Review.Coverage("src/Z","JAVA-001",incomplete?"needs_context":"reviewed",incomplete?"上下文仍需补查":""));
        return Review.report(TestFixtures.request(),"single","test",null,new Review.Result(findings,coverage,
            incomplete?List.of("上下文仍需补查"):List.of(),status));
    }
}
