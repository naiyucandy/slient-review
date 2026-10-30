package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RulePathTemplateTest {
    @Test void namedCaptureSubstitutesOnlyItsBoundSegment(){
        var bindings=Rules.bind("modules/{module}/versions/{version}/code/**","modules/account/versions/2.4/code/a.sql").orElseThrow();
        assertEquals(Map.of("module","account","version","2.4"),bindings);
        assertEquals("modules/account/versions/2.4/registry.txt",Rules.substitute("modules/{module}/versions/{version}/registry.txt",bindings).orElseThrow());
    }
    @Test void wholeSegmentLegacyPlaceholderIsGenericAndActualXxxAlsoMatches(){
        assertEquals("2.4",Rules.bind("modules/xxx/code/**","modules/2.4/code/a.sql").orElseThrow().get("xxx"));
        assertEquals("xxx",Rules.bind("modules/xxx/code/**","modules/xxx/code/a.sql").orElseThrow().get("xxx"));
        assertFalse(Rules.glob("modules/fooxxx/code/**").matcher("modules/2.4/code/a.sql").matches());
    }
    @Test void repeatedPlaceholderMustReferToSameValueAndDoesNotCrossDirectory(){
        assertTrue(Rules.glob("{module}/mirror/{module}/file.json").matcher("a/mirror/a/file.json").matches());
        assertFalse(Rules.glob("{module}/mirror/{module}/file.json").matcher("a/mirror/b/file.json").matches());
        assertFalse(Rules.glob("versions/{version}/file.json").matcher("versions/a/b/file.json").matches());
        assertTrue(Rules.substitute("config/{missing}/x.json",Map.of()).isEmpty());
    }
    @Test void validatesOptionalContextDeclarationsWithoutChangingOrdinaryGlobBehavior(){
        JsonObject json=JsonParser.parseString(TestFixtures.RULES).getAsJsonObject();JsonObject rule=json.getAsJsonArray("rules").get(0).getAsJsonObject();
        rule.add("contextFiles",JsonParser.parseString("[\"config/{version}/rules.json\"]"));
        assertEquals(List.of("config/{version}/rules.json"),Rules.parse(json.toString()).rules().get(0).contextFiles());
        rule.add("contextFiles",JsonParser.parseString("[\"../secret.txt\"]"));assertThrows(IllegalArgumentException.class,()->Rules.parse(json.toString()));
        assertTrue(Rules.parse(TestFixtures.RULES).rules().get(0).contextFiles().isEmpty());
        assertTrue(Rules.glob("src/**/OrderService.java").matcher("src/OrderService.java").matches());
    }
}
