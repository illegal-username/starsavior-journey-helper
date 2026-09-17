package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact source references; item names are never guessed from reward text. */
final class ItemDetails {
    private ItemDetails() {}

    static final class Item {
        final String id, name, description, effect;
        Item(String id, String name, String description, String effect) {
            this.id = id; this.name = name; this.description = description; this.effect = effect;
        }
    }

    static final class Link {
        final Item item;
        final int start, end;
        Link(Item item, int start, int end) { this.item = item; this.start = start; this.end = end; }
    }

    static final class References {
        static final References EMPTY = new References(List.of(), List.of(), List.of());
        final List<Link> condition, success, failure;
        References(List<Link> condition, List<Link> success, List<Link> failure) {
            this.condition = List.copyOf(condition);
            this.success = List.copyOf(success);
            this.failure = List.copyOf(failure);
        }
        boolean sameAs(References other) {
            return same(condition, other.condition) && same(success, other.success) && same(failure, other.failure);
        }
        private static boolean same(List<Link> a, List<Link> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i).start != b.get(i).start || a.get(i).end != b.get(i).end
                        || !a.get(i).item.id.equals(b.get(i).item.id)) return false;
            }
            return true;
        }
    }

    static Map<String, Item> parseCatalog(JSONObject root) throws JSONException {
        if (root.optInt("schema") != 7) {
            if (root.has("items")) throw new JSONException("Item details require schema 7.");
            return Map.of();
        }
        JSONArray rows = root.getJSONArray("items");
        Map<String, Item> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Item item = new Item(string(row, "id"), string(row, "name"),
                    string(row, "description"), string(row, "effect"));
            if (item.id.trim().isEmpty() || item.name.trim().isEmpty() || result.containsKey(item.id)) {
                throw new JSONException("Invalid or duplicate item.");
            }
            result.put(item.id, item);
        }
        return Map.copyOf(result);
    }

    static References parse(JSONObject row, Map<String, Item> items, boolean enabled) throws JSONException {
        return new References(links(row, "condition", items, enabled),
                links(row, "success", items, enabled), links(row, "failure", items, enabled));
    }

    private static List<Link> links(JSONObject row, String field, Map<String, Item> items,
                                    boolean enabled) throws JSONException {
        if (!row.has(field + "Items")) return List.of();
        if (!enabled) throw new JSONException("Item links require schema 7.");
        String text = string(row, field);
        JSONArray rows = row.getJSONArray(field + "Items");
        List<Link> result = new ArrayList<>();
        int previous = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject link = rows.getJSONObject(i);
            Item item = items.get(string(link, "itemId"));
            int start = integer(link, "start"), end = integer(link, "end");
            if (item == null || start < previous || end <= start || end > text.length()
                    || !text.substring(start, end).equals(item.name)) {
                throw new JSONException("Invalid item link range or reference.");
            }
            result.add(new Link(item, start, end));
            previous = end;
        }
        return result;
    }

    private static String string(JSONObject row, String field) throws JSONException {
        Object value = row.get(field);
        if (!(value instanceof String)) throw new JSONException("Invalid item string: " + field);
        return (String) value;
    }

    private static int integer(JSONObject row, String field) throws JSONException {
        Object value = row.get(field);
        if (!(value instanceof Number)) throw new JSONException("Invalid item offset.");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) {
            throw new JSONException("Invalid item offset.");
        }
        return (int) number;
    }
}
