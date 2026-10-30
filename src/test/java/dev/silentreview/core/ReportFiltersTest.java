package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportFiltersTest {
    @Test void sameRevisionAndPathInDifferentRepositoriesRemainDistinctWhenFiltering(){
        Review.Report one=report("/repo-a","warning"),two=report("/repo-b","error");
        Map<String,Reports.Resolution> states=Map.of(two.id(),new Reports.Resolution(two.id(),Reports.Decision.CLOSED,"","","",List.of()));
        assertEquals(List.of(one),ReportFilters.select(List.of(one,two),states,"/repo-a",Reports.Decision.UNREVIEWED,"warning"));
        assertEquals(List.of(two),ReportFilters.select(List.of(one,two),states,null,Reports.Decision.CLOSED,"error"));
        assertEquals(List.of(one,two),ReportFilters.select(List.of(one,two),states,null,null,null));
        assertEquals(1,two.result().findings().size());assertEquals(Review.Status.COMPLETED,two.result().status());
    }
    @Test void displaySelectionUsesRealRepositorySetAndEmptySelectionShowsNothing(){
        Review.Report first=report("/repo-a","warning"),second=report("/repo-b","error");var reports=List.of(first,second);
        assertEquals(reports,ReportFilters.selectForDisplay(reports,Map.of(),null,null,null));
        assertEquals(List.of(),ReportFilters.selectForDisplay(reports,Map.of(),Set.of(),null,null));
        assertEquals(List.of(second),ReportFilters.selectForDisplay(reports,Map.of(),Set.of("/repo-b"),null,null));
        assertEquals(List.of(first),ReportFilters.selectForDisplay(reports,Map.of(),Set.of("/repo-a","/repo-b"),Reports.Decision.UNREVIEWED,"warning"));
        assertEquals(1,first.result().findings().size());assertEquals(1,second.result().findings().size());
    }
    @Test void confirmedDisplayGroupIncludesLegacyDecisionsWithoutRewritingTheirAudit(){
        Review.Report pending=report("/repo","warning"),confirmed=report("/repo","warning"),accepted=report("/repo","warning"),closed=report("/repo","warning");
        var reports=List.of(pending,confirmed,accepted,closed);Map<String,Reports.Resolution> states=new LinkedHashMap<>();
        states.put(confirmed.id(),new Reports.Resolution(confirmed.id(),Reports.Decision.CONFIRMED,"","","",List.of()));
        states.put(accepted.id(),new Reports.Resolution(accepted.id(),Reports.Decision.ACCEPTED,"","","",List.of()));
        states.put(closed.id(),new Reports.Resolution(closed.id(),Reports.Decision.CLOSED,"","","",List.of()));
        var original=Map.copyOf(states);
        assertEquals(List.of(confirmed,accepted,closed),ReportFilters.selectForDisplay(reports,states,null,Reports.Decision.CONFIRMED,null));
        assertEquals(List.of(pending),ReportFilters.selectForDisplay(reports,states,null,Reports.Decision.UNREVIEWED,null));
        assertEquals(List.of(accepted),ReportFilters.selectForDisplay(reports,states,null,Reports.Decision.ACCEPTED,null));
        assertEquals(List.of(confirmed),ReportFilters.select(reports,states,null,Reports.Decision.CONFIRMED,null));
        assertEquals(original,states);
    }
    private static Review.Report report(String root,String severity){
        var req=TestFixtures.request();var source=Review.report(req,"single","test",null,Review.validate(req,TestFixtures.response(true).toString()));
        var f=source.result().findings().get(0);
        var finding=new Review.Finding(f.ruleId(),f.kind(),severity,f.filePath(),f.side(),f.startLine(),f.endLine(),f.evidence(),f.explanation(),f.suggestion(),f.changeRelation());
        return new Review.Report(source.id(),source.createdAt(),root,source.branch(),source.base(),source.target(),source.scope(),source.ruleSetId(),source.ruleSetName(),source.ruleSetVersion(),source.fingerprint(),source.ruleDefinitions(),source.previousPaths(),source.instructionVersion(),source.model(),source.usage(),new Review.Result(List.of(finding),source.result().coverage(),source.result().limitations(),source.result().status()));
    }
}
