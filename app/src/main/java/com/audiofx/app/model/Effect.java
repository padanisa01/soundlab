package com.audiofx.app.model;

import com.audiofx.app.LibraryDb;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/**
 * One effect in a track's history: the domain view of a LibraryDb.Op
 * row (design doc 4.2). Plain Java; the only dependencies are
 * org.json and the LibraryDb.Op record it wraps.
 *
 * Params are carried as the RAW paramsJson text, retained verbatim.
 * Java treats params as opaque (design doc 10.1): they are stored
 * and forwarded, never invented or re-serialized. That is why the
 * render wire string is NOT built from {@link #toOpJson()} — a
 * JSONObject round-trip (parse + re-serialize) could reorder keys
 * or change spacing and break the byte-for-byte wire contract.
 * Instead, {@link EffectHistory#toOpsJson()} emits each renderable
 * effect through {@link #rawOpJson()}, which splices the raw params
 * text in untouched, exactly as MainActivity.buildOpsJson did.
 * {@link #toOpJson()} exists for callers that want the op as an
 * object; its params are the parsed view from {@link #params()}.
 */
public final class Effect {

    /** History status, derived per design doc 4.3 (no stored flag):
     * a renderable op present in the history is APPLIED; a
     * non-renderable (display-only) entry is an ANNOTATION. */
    public enum Status {
        APPLIED,
        ANNOTATION
    }

    /** Op type name (LibraryDb.Op.opType), e.g. "pitch_shift". */
    public final String name;
    /** Raw params JSON text, verbatim from the ops table. */
    public final String paramsJson;
    /** Human-readable history label. */
    public final String label;
    /** History timestamp (ms since epoch). */
    public final long createdAt;
    /** Ops-table row id (0 = unsaved). */
    public final long rowId;

    private final boolean renderable;

    public Effect(String name, String paramsJson, String label,
            long createdAt, long rowId, boolean renderable) {
        this.name = name;
        this.paramsJson = paramsJson;
        this.label = label;
        this.createdAt = createdAt;
        this.rowId = rowId;
        this.renderable = renderable;
    }

    /** Wrap a persisted op row, resolving renderability against the
     * manifest-derived renderable set (design doc 10.2). The set is
     * injected so this package never depends on MainActivity.
     *
     * @throws IllegalStateException if renderableOps is null — a
     * missing set must fail loudly, never silently mark every op
     * non-renderable (the historical pitch_shift silent-drop). */
    public static Effect fromOp(LibraryDb.Op op, Set<String> renderableOps) {
        if (renderableOps == null) {
            throw new IllegalStateException(
                    "renderableOps not loaded: the ops manifest set must"
                            + " be provided before wrapping ops");
        }
        return new Effect(op.opType, op.paramsJson, op.label,
                op.createdAt, op.rowId, renderableOps.contains(op.opType));
    }

    /** Parsed params, on demand. Returns an empty JSONObject when
     * the raw text is null, empty, or not a JSON object — params
     * semantics belong to Python (design doc 10.1), so a value is
     * never invented here. */
    public JSONObject params() {
        if (paramsJson == null || paramsJson.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(paramsJson);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** True for ops that render_chain executes (manifest set). */
    public boolean renderable() {
        return renderable;
    }

    /** Derived status (design doc 4.3). */
    public Status status() {
        return renderable ? Status.APPLIED : Status.ANNOTATION;
    }

    /** This op as a JSONObject: {"op": name, "params": {...}}.
     * Object view only — the render wire string comes from
     * {@link EffectHistory#toOpsJson()} via {@link #rawOpJson()},
     * because the wire contract is byte-for-byte and a JSONObject
     * re-serialization is not guaranteed to reproduce the raw
     * params text. */
    public JSONObject toOpJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("op", name);
            out.put("params", params());
        } catch (JSONException ignored) {
            // put() only throws for a null key or a NaN/Infinite
            // double value; neither can occur for "op"/"params"
            // with the values above.
        }
        return out;
    }

    /** The exact wire fragment for this op, byte-for-byte as the
     * pre-model buildOpsJson built it: {"op":<quoted name>,
     * "params":<raw paramsJson appended verbatim>}. A null
     * paramsJson appends the literal "null", matching the old
     * StringBuilder behavior. Package-private: only EffectHistory
     * assembles wire strings. */
    String rawOpJson() {
        return "{\"op\":" + JSONObject.quote(name)
                + ",\"params\":" + paramsJson + "}";
    }
}
