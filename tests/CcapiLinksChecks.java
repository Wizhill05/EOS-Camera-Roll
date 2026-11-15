package dev.aryan.cameralinklab;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONTokener;

public final class CcapiLinksChecks {
    private static int checks;
    private static void expect(String json, String... paths) throws Exception {
        Set<String> actual = new LinkedHashSet<>();
        CcapiLinks.collect(new JSONTokener(json).nextValue(), actual);
        Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(paths));
        if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + ", received " + actual);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        // Contents responses and directory/file lists use arrays of URL strings.
        expect("{\"url\":[\"/ccapi/ver140/contents/card1\"]}", "/ccapi/ver140/contents/card1");
        expect("{\"url\":[\"/ccapi/ver140/contents/card1/DCIM\",\"/ccapi/ver140/contents/card1/XFVC\"]}",
                "/ccapi/ver140/contents/card1/DCIM", "/ccapi/ver140/contents/card1/XFVC");
        expect("[\"/ccapi/ver140/contents/card1/XFVC/REEL_0001/clip.MP4\",\"/ccapi/ver140/contents/card1/DCIM/IMG_0001.CR3\"]",
                "/ccapi/ver140/contents/card1/XFVC/REEL_0001/clip.MP4", "/ccapi/ver140/contents/card1/DCIM/IMG_0001.CR3");
        expect("{\"ver140\":[{\"url\":\"/ccapi/ver140/contents\",\"get\":true}]}", "/ccapi/ver140/contents");
        expect("{\"nested\":[[\"/ccapi/ver140/contents\"]],\"url\":\"/ccapi/ver140/contents\"}", "/ccapi/ver140/contents");
        expect("{\"url\":[],\"value\":\"No list of APIs\",\"count\":0,\"available\":false,\"extra\":null}");
        expect("{\"url\":[\"http://camera:8080/ccapi/ver140/contents\",\"not a link\"]}", "http://camera:8080/ccapi/ver140/contents");
        System.out.println("All " + checks + " CCAPI link parsing checks passed.");
    }
}
