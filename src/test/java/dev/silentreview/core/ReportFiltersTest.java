package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportFiltersTest {
    @Test void sameRevisionAndPathInDifferentRepositoriesRemainDistinctWhenFiltering(){
        Review.Report one=report("/repo-a","warning"),two=report("/repo-b","error");
        Map<String,Reports.Resolution> states=Map.of(two.id(),new Reports.Resolution(two.id(),Reports.Decision.CONFIRMED,"","","",List.of()));
        assertEquals(List.of(one),ReportFilters.select(List.of(one,two),states,Set.of("/repo-a"),Reports.Decision.PENDING,"warning"));
        assertEquals(List.of(two),ReportFilters.select(List.of(one,two),states,(Set<String>)null,Reports.Decision.CONFIRMED,"error"));
        assertEquals(List.of(one,two),ReportFilters.select(List.of(one,two),states,Set.of("/repo-a","/repo-b"),null,null));
        assertEquals(List.of(two),ReportFilters.select(List.of(one,two),states,Set.of("/repo-b"),null,null));
        assertEquals(List.of(),ReportFilters.select(List.of(one,two),states,Set.of(),null,null));
        assertEquals(List.of(one,two),ReportFilters.select(List.of(one,two),states,(Set<String>)null,null,null));
        assertEquals(1,two.result().findings().size());assertEquals(Review.Status.COMPLETED,two.result().status());
    }
    private static Review.Report report(String root,String severity){
        var req=TestFixtures.request();var source=Review.report(req,"single","test",null,Review.validate(req,TestFixtures.response(true).toString()));
        var f=source.result().findings().get(0);
        var finding=new Review.Finding(f.ruleId(),f.kind(),severity,f.filePath(),f.side(),f.startLine(),f.endLine(),f.evidence(),f.explanation(),f.suggestion(),f.changeRelation());
        return new Review.Report(source.id(),source.createdAt(),root,source.branch(),source.base(),source.target(),source.scope(),source.ruleSetId(),source.ruleSetName(),source.ruleSetVersion(),source.fingerprint(),source.ruleDefinitions(),source.previousPaths(),source.instructionVersion(),source.model(),source.usage(),new Review.Result(List.of(finding),source.result().coverage(),source.result().limitations(),source.result().status()));
    }
}
