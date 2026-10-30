package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test void loadsRulesAndStableFingerprint(){
        var a=Rules.parse(TestFixtures.RULES);var b=Rules.parse(TestFixtures.RULES);
        assertEquals(1,a.rules().size());assertEquals(a.fingerprint(),b.fingerprint());assertEquals(64,a.fingerprint().length());
    }
    @Test void contentChangeProducesNewFingerprint(){assertNotEquals(Rules.parse(TestFixtures.RULES).fingerprint(),Rules.parse(TestFixtures.RULES.replace("禁止控制台打印","禁止控制台输出")).fingerprint());}
    @Test void doubleStarMatchesZeroOrMoreDirectories(){var rule=Rules.parse(TestFixtures.RULES).rules().get(0);assertTrue(rule.appliesTo("src/OrderService.java"));assertTrue(rule.appliesTo("src/main/java/OrderService.java"));}
    @Test void exclusionWinsAndMatchingIsCaseSensitive(){var r=Rules.parse(TestFixtures.RULES).rules().get(0);assertFalse(r.appliesTo("src/vendor/OrderService.java"));assertFalse(r.appliesTo("src/orderservice.java"));}
    @Test void rejectsUnsupportedVersion(){assertThrows(IllegalArgumentException.class,()->Rules.parse(TestFixtures.RULES.replace("\"1.0\"","\"2.0\"")));}
    @Test void rejectsEmptyRules(){assertThrows(IllegalArgumentException.class,()->Rules.parse("{\"schemaVersion\":\"1.0\",\"ruleSetId\":\"x\",\"name\":\"x\",\"version\":\"1\",\"rules\":[]}"));}
    @Test void rejectsDuplicateIds(){var root=com.google.gson.JsonParser.parseString(TestFixtures.RULES).getAsJsonObject();root.getAsJsonArray("rules").add(root.getAsJsonArray("rules").get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->Rules.parse(root.toString()));}
    @Test void rejectsInvalidSeverity(){assertThrows(IllegalArgumentException.class,()->Rules.parse(TestFixtures.RULES.replace("warning","fatal")));}
    @Test void rejectsMissingSource(){var root=com.google.gson.JsonParser.parseString(TestFixtures.RULES).getAsJsonObject();root.getAsJsonArray("rules").get(0).getAsJsonObject().remove("source");assertThrows(IllegalArgumentException.class,()->Rules.parse(root.toString()));}
    @Test void rejectsTraversalAndUnsupportedGlobSyntax(){for(String glob:new String[]{"../src/**","/src/**","C:/src/**","src\\**","src/[ab].java","src/?.java"})assertThrows(IllegalArgumentException.class,()->Rules.glob(glob));}
    @Test void rejectsCommentsUnquotedNamesAndDuplicateJsonKeys(){
        assertThrows(IllegalArgumentException.class,()->Rules.parse(TestFixtures.RULES.replace("\"schemaVersion\"","schemaVersion")));
        assertThrows(IllegalArgumentException.class,()->Rules.parse("/* comment */"+TestFixtures.RULES));
        assertThrows(IllegalArgumentException.class,()->Rules.parse(TestFixtures.RULES.replace("\"schemaVersion\":\"1.0\"","\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"")));
    }
}
