package com.nada.people;

import java.util.LinkedHashMap;
import java.util.Map;

/** A person in the network. The name is stored exactly as supplied. */
record Person(long id, String name) {

    /** JSON-ready representation: {"id": 1, "name": "Asha"}. */
    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("name", name);
        return json;
    }
}
