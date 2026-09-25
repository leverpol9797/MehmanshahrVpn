package com.firstham.aethergui;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The label arrays are indexed by the saved preference, so an item moved in
 * the wrong array silently switches an existing user to a different mode.
 * The pairing with the string constants in VpnConnectionController is not
 * visible at the point either is edited, which is how the Persian arrays once
 * shipped with seven English rows in the default resource set.
 */
public final class LabelArrayAlignmentTest {

    private static final Pattern ARRAY = Pattern.compile(
            "<string-array name=\"([^\"]+)\">(.*?)</string-array>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);

    private static Map<String, List<String>> arraysIn(String file) throws Exception {
        String source = new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = ARRAY.matcher(source);
        while (m.find()) {
            List<String> items = new ArrayList<>();
            Matcher i = ITEM.matcher(m.group(2));
            while (i.find()) items.add(i.group(1));
            out.put(m.group(1), items);
        }
        return out;
    }

    private static int constantCount(String source, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*\\{([^}]*)\\}").matcher(source);
        if (!m.find()) return -1;
        int n = 0;
        for (String part : m.group(1).split(",")) if (!part.trim().isEmpty()) n++;
        return n;
    }

    private static String controller() throws Exception {
        File f = new File("src/main/java/com/firstham/aethergui/VpnConnectionController.java");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void persianAndEnglishArraysHaveTheSameShape() throws Exception {
        Map<String, List<String>> fa = arraysIn("src/main/res/values/arrays.xml");
        Map<String, List<String>> en = arraysIn("src/main/res/values-en/arrays.xml");
        assertEquals("array names differ between values/ and values-en/", fa.keySet(), en.keySet());
        for (String name : fa.keySet()) {
            assertEquals(name + " item count differs between the two locales",
                    fa.get(name).size(), en.get(name).size());
        }
    }

    @Test public void theDefaultLocaleIsPersian() throws Exception {
        // Every user-facing array must carry at least one Persian item, or the
        // selector falls back to English in the app's own language.
        Map<String, List<String>> fa = arraysIn("src/main/res/values/arrays.xml");
        for (String name : fa.keySet()) {
            if (name.equals("protocol_labels") || name.equals("transport_labels")) continue;  // product names
            boolean persian = false;
            for (String item : fa.get(name)) {
                for (char c : item.toCharArray()) if (c >= 0x0600 && c <= 0x06FF) persian = true;
            }
            assertTrue(name + " has no Persian label in the default resource set", persian);
        }
    }

    @Test public void everyLabelArrayLinesUpWithItsConstant() throws Exception {
        String source = controller();
        Object[][] pairs = {
                {"PROTOCOLS", "protocol_labels"}, {"SCANS", "scan_labels"}, {"IP_MODES", "ip_labels"},
        };
        for (Object[] pair : pairs) {
            int constants = constantCount(source, (String) pair[0]);
            int items = arraysIn("src/main/res/values/arrays.xml").get((String) pair[1]).size();
            assertEquals(pair[0] + " and " + pair[1] + " are indexed by the same preference",
                    constants, items);
        }
    }
}
