package com.diaryproxy.app;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class JsonPathUtils {

    private JsonPathUtils() {
    }

    static Object getValue(JSONObject root, String path) {
        if (root == null || TextUtils.isEmpty(path)) {
            return null;
        }
        Object current = root;
        List<PathToken> tokens = parse(path);
        for (PathToken token : tokens) {
            current = descend(current, token);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static String getString(JSONObject root, String path) {
        Object value = getValue(root, path);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(value);
    }

    static JSONObject getObject(JSONObject root, String path) {
        Object value = getValue(root, path);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    static JSONArray getArray(JSONObject root, String path) {
        Object value = getValue(root, path);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    static boolean setValue(JSONObject root, String path, Object value) {
        if (root == null || TextUtils.isEmpty(path)) {
            return false;
        }
        List<PathToken> tokens = parse(path);
        if (tokens.isEmpty()) {
            return false;
        }

        Object current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            PathToken token = tokens.get(i);
            PathToken next = tokens.get(i + 1);
            current = descendOrCreate(current, token, next);
            if (current == null) {
                return false;
            }
        }

        return assign(current, tokens.get(tokens.size() - 1), value);
    }

    private static Object descend(Object current, PathToken token) {
        if (current == null) {
            return null;
        }

        Object next = current;
        if (!TextUtils.isEmpty(token.key)) {
            if (!(next instanceof JSONObject)) {
                return null;
            }
            JSONObject object = (JSONObject) next;
            if (!object.has(token.key)) {
                return null;
            }
            next = object.opt(token.key);
        }

        if (token.hasIndex()) {
            if (!(next instanceof JSONArray)) {
                return null;
            }
            JSONArray array = (JSONArray) next;
            int index = resolveIndex(array.length(), token.index);
            if (index < 0 || index >= array.length()) {
                return null;
            }
            next = array.opt(index);
        }
        return next;
    }

    private static Object descendOrCreate(Object current, PathToken token, PathToken nextToken) {
        if (current == null) {
            return null;
        }

        Object next = current;
        if (!TextUtils.isEmpty(token.key)) {
            if (!(next instanceof JSONObject)) {
                return null;
            }
            JSONObject object = (JSONObject) next;
            Object child = object.opt(token.key);
            if (child == null || child == JSONObject.NULL) {
                child = token.hasIndex() ? new JSONArray() : createContainer(nextToken);
                try {
                    object.put(token.key, child);
                } catch (Exception ignored) {
                    return null;
                }
            }
            next = child;
        }

        if (token.hasIndex()) {
            if (!(next instanceof JSONArray)) {
                return null;
            }
            JSONArray array = (JSONArray) next;
            int index = resolveIndexForCreate(array.length(), token.index);
            if (index < 0) {
                return null;
            }
            ensureArraySize(array, index + 1);
            Object child = array.opt(index);
            if (child == null || child == JSONObject.NULL) {
                child = createContainer(nextToken);
                try {
                    array.put(index, child);
                } catch (Exception ignored) {
                    return null;
                }
            }
            next = child;
        }
        return next;
    }

    private static boolean assign(Object current, PathToken token, Object value) {
        if (current == null) {
            return false;
        }

        if (!TextUtils.isEmpty(token.key)) {
            if (!(current instanceof JSONObject)) {
                return false;
            }
            JSONObject object = (JSONObject) current;
            if (token.hasIndex()) {
                Object child = object.opt(token.key);
                if (!(child instanceof JSONArray)) {
                    child = new JSONArray();
                    try {
                        object.put(token.key, child);
                    } catch (Exception ignored) {
                        return false;
                    }
                }
                JSONArray array = (JSONArray) child;
                int index = resolveIndexForCreate(array.length(), token.index);
                if (index < 0) {
                    return false;
                }
                ensureArraySize(array, index + 1);
                try {
                    array.put(index, wrapNull(value));
                    object.put(token.key, array);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
            try {
                object.put(token.key, wrapNull(value));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        if (!(current instanceof JSONArray) || !token.hasIndex()) {
            return false;
        }
        JSONArray array = (JSONArray) current;
        int index = resolveIndexForCreate(array.length(), token.index);
        if (index < 0) {
            return false;
        }
        ensureArraySize(array, index + 1);
        try {
            array.put(index, wrapNull(value));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Object wrapNull(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static Object createContainer(PathToken nextToken) {
        return nextToken != null && nextToken.hasIndex() && TextUtils.isEmpty(nextToken.key)
                ? new JSONArray()
                : new JSONObject();
    }

    private static void ensureArraySize(JSONArray array, int desiredSize) {
        while (array.length() < desiredSize) {
            array.put(JSONObject.NULL);
        }
    }

    private static int resolveIndex(int length, int rawIndex) {
        if (rawIndex >= 0) {
            return rawIndex;
        }
        int resolved = length + rawIndex;
        return resolved;
    }

    private static int resolveIndexForCreate(int length, int rawIndex) {
        if (rawIndex >= 0) {
            return rawIndex;
        }
        int resolved = length + rawIndex;
        if (resolved >= 0) {
            return resolved;
        }
        if (rawIndex == -1 && length == 0) {
            return 0;
        }
        return -1;
    }

    private static List<PathToken> parse(String path) {
        List<PathToken> tokens = new ArrayList<>();
        if (TextUtils.isEmpty(path)) {
            return tokens;
        }
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (TextUtils.isEmpty(segment)) {
                continue;
            }
            tokens.add(parseToken(segment.trim()));
        }
        return tokens;
    }

    private static PathToken parseToken(String segment) {
        int bracket = segment.indexOf('[');
        if (bracket < 0 || !segment.endsWith("]")) {
            return new PathToken(segment, null);
        }
        String key = bracket == 0 ? "" : segment.substring(0, bracket);
        String indexText = segment.substring(bracket + 1, segment.length() - 1).trim();
        Integer index = null;
        try {
            index = Integer.parseInt(indexText);
        } catch (Exception ignored) {
        }
        return new PathToken(key, index);
    }

    private static final class PathToken {
        final String key;
        final Integer index;

        PathToken(String key, Integer index) {
            this.key = key;
            this.index = index;
        }

        boolean hasIndex() {
            return index != null;
        }
    }
}
