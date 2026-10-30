package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReviewTest {
    @Test void acceptsVerifiedFindingAndCoverage(){var result=Review.validate(TestFixtures.request(),TestFixtures.response(true).toString());assertEquals(Review.Status.COMPLETED,result.status());assertEquals(1,result.findings().size());}
    @Test void completedZeroFindingsRequiresFullCoverage(){var result=Review.validate(TestFixtures.request(),TestFixtures.response(false).toString());assertEquals(Review.Status.COMPLETED,result.status());assertTrue(result.findings().isEmpty());}
    @Test void missingCoverageCannotPass(){var json=TestFixtures.response(false);json.add("coverage",new JsonArray());var result=Review.validate(TestFixtures.request(),json.toString());assertEquals(Review.Status.PARTIAL,result.status());assertEquals("failed",result.coverage().get(0).status());}
    @Test void unknownRulesFilesLinesAndEvidenceAreRejected(){
        for(var mutation:Map.of("ruleId","INVENTED","filePath","other.java","startLine",999,"evidence","invented()" ).entrySet()){
            var json=TestFixtures.response(true);var f=json.getAsJsonArray("findings").get(0).getAsJsonObject();
            if(mutation.getValue() instanceof Number n)f.addProperty(mutation.getKey(),n);else f.addProperty(mutation.getKey(),mutation.getValue().toString());
            var result=Review.validate(TestFixtures.request(),json.toString());assertEquals(Review.Status.PARTIAL,result.status());assertTrue(result.findings().isEmpty());
        }
    }
    @Test void findingOutsideChangedLinesNeedsConfirmation(){var json=TestFixtures.response(true);var f=json.getAsJsonArray("findings").get(0).getAsJsonObject();f.addProperty("startLine",1);f.addProperty("endLine",1);f.addProperty("evidence","class OrderService {");var result=Review.validate(TestFixtures.request(),json.toString());assertEquals("needs_confirmation",result.findings().get(0).kind());assertEquals(Review.Status.PARTIAL,result.status());}
    @Test void explicitMissingContextIsPartial(){var json=TestFixtures.response(false);json.getAsJsonArray("coverage").get(0).getAsJsonObject().addProperty("status","needs_context");assertEquals(Review.Status.PARTIAL,Review.validate(TestFixtures.request(),json.toString()).status());}
    @Test void duplicateFindingsAreDeduplicated(){var json=TestFixtures.response(true);json.getAsJsonArray("findings").add(json.getAsJsonArray("findings").get(0).deepCopy());assertEquals(1,Review.validate(TestFixtures.request(),json.toString()).findings().size());}
    @Test void emptyOrMalformedResponseIsNotSuccess(){for(String text:List.of("","{}","{\"findings\":[]}","{broken"))assertThrows(IllegalArgumentException.class,()->Review.validate(TestFixtures.request(),text));}
    @Test void limitationsPreventCompleteStatus(){var json=TestFixtures.response(false);json.getAsJsonArray("limitations").add("跨文件上下文不足");assertEquals(Review.Status.PARTIAL,Review.validate(TestFixtures.request(),json.toString()).status());}
    @Test void noApplicableRulesDoesNotMeanPassed(){var req=TestFixtures.request();var r=new Review.Request(req.rules(),req.changes(),List.of(),"{}");var json=TestFixtures.response(false);json.add("coverage",new JsonArray());assertEquals(Review.Status.NOT_APPLICABLE,Review.validate(r,json.toString()).status());}
    @Test void reportRetainsRuleSourceAndVersions(){var req=TestFixtures.request();var result=Review.validate(req,TestFixtures.response(true).toString());var report=Review.report(req,"single","test-model",null,result);String md=Reports.markdown(report);assertTrue(md.contains("禁止控制台打印"));assertTrue(md.contains(req.rules().fingerprint()));assertEquals(req.changes().target(),report.target());assertTrue(md.contains("System.out.println"));}
}
