package dev.aryan.cameralinklab;

import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CcapiLinks {
    private CcapiLinks() {}

    static void collect(Object value, Set<String> links) throws JSONException {
        if (value instanceof String) {
            String text = (String)value;
            if (text.startsWith("/ccapi") || text.matches("https?://.*?/ccapi.*")) links.add(text);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collect(object.get(keys.next()), links);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); i++) collect(array.get(i), links);
        }
    }
}
