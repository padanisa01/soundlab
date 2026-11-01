package com.audiofx.app.model;

import com.audiofx.app.LibraryDb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A track's ordered effect history (design doc 4.2): the domain
 * view of the ops-table rows for one track, in seq order. Wraps
 * LibraryDb.Op data; persistence stays in LibraryDb (this class
 * never touches the DB — {@link #popLast()} is an in-memory pop
 * only, for future slices; undo still persists through
 * LibraryDb.popLastOp).
 */
public final class EffectHistory {

    private final List<Effect> effects;

    public EffectHistory() {
        this.effects = new ArrayList<>();
    }

    private EffectHistory(List<Effect> effects) {
        this.effects = new ArrayList<>(effects);
    }

    /** Build the history for a track from its persisted op rows
     * (LibraryDb.listOps order: seq ASC), resolving each op's
     * renderability against the manifest-derived set. */
    public static EffectHistory fromOps(List<LibraryDb.Op> ops,
            Set<String> renderableOps) {
        List<Effect> built = new ArrayList<>();
        for (LibraryDb.Op op : ops) {
            built.add(Effect.fromOp(op, renderableOps));
        }
        return new EffectHistory(built);
    }

    /** The effects in history (seq) order, as an unmodifiable live
     * view: it reflects later {@link #add}/{@link #popLast()} calls
     * but cannot be mutated through the returned list. */
    public List<Effect> effects() {
        return Collections.unmodifiableList(effects);
    }

    /** Append an effect at the end of the history (in memory). */
    public void add(Effect e) {
        effects.add(e);
    }

    /** Remove and return the last effect (in memory only), or null
     * when the history is empty. */
    public Effect popLast() {
        if (effects.isEmpty()) {
            return null;
        }
        return effects.remove(effects.size() - 1);
    }

    /** Undo depth: the number of renderable effects in the history. */
    public int undoDepth() {
        int n = 0;
        for (Effect e : effects) {
            if (e.renderable()) {
                n++;
            }
        }
        return n;
    }

    /** The last renderable effect, or null when none is renderable
     * (the undo target scan). */
    public Effect lastRenderable() {
        for (int i = effects.size() - 1; i >= 0; i--) {
            if (effects.get(i).renderable()) {
                return effects.get(i);
            }
        }
        return null;
    }

    /** ops_json for render_chain: exactly the string the pre-model
     * MainActivity.buildOpsJson produced (design doc 10.1,
     * byte-for-byte). Construction mirrors it token for token:
     * "[" + one fragment per RENDERABLE effect in history order,
     * joined with "," + "]", where each fragment is
     * {"op":JSONObject.quote(name),"params":<raw params verbatim>}
     * (see Effect.rawOpJson). Non-renderable effects are skipped
     * entirely. */
    public String toOpsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Effect e : effects) {
            if (!e.renderable()) {
                continue; // livefx / fork are display-only
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(e.rawOpJson());
        }
        sb.append("]");
        return sb.toString();
    }
}
