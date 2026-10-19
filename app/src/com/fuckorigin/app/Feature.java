package com.fuckorigin.app;

import android.content.Context;

public final class Feature {
    public static final String KIND_HOOK = "HOOK";
    public static final String KIND_ROOT = "ROOT";
    public static final String KIND_SETTINGS = "SETTINGS";
    public static final String KIND_PKG = "PKG";
    public static final String KIND_DIAG = "DIAG";
    public static final String KIND_META = "META";
    public static final String KIND_GROUP = "GROUP";

    public final String id, domain, name, risk, status, kind, target, note;
    /** GROUP 类型的成员 featureId；其它类型为空数组 */
    public String[] children = new String[0];

    public Feature(String id, String domain, String name, String risk, String status,
                   String kind, String target, String note) {
        this.id = id; this.domain = domain; this.name = name; this.risk = risk;
        this.status = status; this.kind = kind; this.target = target; this.note = note;
    }

    public boolean enabled(Context c) { return Prefs.get(c, "f." + id, false); }
    public void setEnabled(Context c, boolean v) { Prefs.set(c, "f." + id, v); }
    public boolean toggleable() { return !KIND_DIAG.equals(kind) && !KIND_META.equals(kind); }
    public boolean isGroup() { return KIND_GROUP.equals(kind); }
}
