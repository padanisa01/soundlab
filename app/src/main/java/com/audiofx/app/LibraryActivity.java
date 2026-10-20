package com.audiofx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recordings library screen (Worker D).
 *
 * RecyclerView, newest first. Each row shows the entry name, recorded
 * date/time + duration + size, up to 3 effect chips (+ a "+N" chip), a teal
 * dot when the entry has exports, and an overflow button. Tap a row to open
 * it in MainActivity; long-press the row or tap overflow for
 * Rename / Duplicate / Delete.
 */
public class LibraryActivity extends Activity {

    /** Intent extra key: the library entry id to open in MainActivity. */
    public static final String EXTRA_ENTRY_ID = "entry_id";

    private static final long TWO_GB = 2L * 1024 * 1024 * 1024;
    private static final int MAX_CHIPS = 3;

    private LibraryDb libraryDb;
    private LibraryAdapter adapter;
    private TextView subtitleView;
    private TextView warningView;
    private TextView emptyView;
    private RecyclerView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        Analytics.libraryViewed(this);
        libraryDb = new LibraryDb(this);
        subtitleView = findViewById(R.id.library_subtitle);
        warningView = findViewById(R.id.library_warning);
        emptyView = findViewById(R.id.library_empty);
        listView = findViewById(R.id.library_list);
        listView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LibraryAdapter();
        listView.setAdapter(adapter);
        loadEntries();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEntries();
    }

    @Override
    protected void onDestroy() {
        if (libraryDb != null) {
            libraryDb.close();
        }
        super.onDestroy();
    }

    /** Loads entries + per-row ops/exports off the UI thread, then binds. */
    private void loadEntries() {
        new Thread(() -> {
            List<RowData> rows = new ArrayList<>();
            List<LibraryDb.LibraryEntry> entries = libraryDb.listEntries();
            for (LibraryDb.LibraryEntry e : entries) {
                List<LibraryDb.Op> ops = listOpsAcrossTracks(e.id);
                boolean hasExports = !libraryDb.listExports(e.id).isEmpty();
                rows.add(new RowData(e, ops, hasExports));
            }
            final long totalBytes = libraryDb.librarySizeBytes();
            runOnUiThread(() -> {
                adapter.setRows(rows);
                updateHeader(rows.size(), totalBytes);
            });
        }).start();
    }

    /**
     * Effect chips for a row: ops across all of the entry's tracks, in
     * track order. Read-only and cheap: this never creates tracks, so a
     * legacy entry that has not been opened in the editor yet shows "No
     * effects yet" until its Track 1 is adopted there (ensureTracks runs
     * on open in MainActivity and re-points the old ops at it).
     */
    private List<LibraryDb.Op> listOpsAcrossTracks(String entryId) {
        List<LibraryDb.Op> ops = new ArrayList<>();
        try {
            for (LibraryDb.Track t : libraryDb.listTracks(entryId)) {
                ops.addAll(libraryDb.listOps(entryId, t.id));
            }
        } catch (Exception ignored) {
        }
        return ops;
    }

    private void updateHeader(int count, long totalBytes) {
        String takes = count == 1 ? "1 take" : count + " takes";
        subtitleView.setText(takes + " · " + formatBytes(totalBytes));
        if (totalBytes > TWO_GB) {
            warningView.setVisibility(View.VISIBLE);
            warningView.setText("Library over 2 GB: consider deleting old takes");
        } else {
            warningView.setVisibility(View.GONE);
        }
        boolean empty = count == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Row actions
    // ------------------------------------------------------------------

    private void openEntry(String entryId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(EXTRA_ENTRY_ID, entryId);
        startActivity(intent);
    }

    private void showOptionsDialog(String entryId, String name) {
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle(name)
                .setItems(new CharSequence[]{"Rename", "Duplicate", "Delete"},
                        (d, which) -> {
                            if (which == 0) {
                                showRenameDialog(entryId, name);
                            } else if (which == 1) {
                                duplicateEntry(entryId);
                            } else {
                                showDeleteDialog(entryId, name);
                            }
                        })
                .show();
    }

    private void showRenameDialog(String entryId, String oldName) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(oldName);
        input.setSelectAllOnFocus(true);
        input.setTextColor(getColor(R.color.sl_txt));
        input.setHintTextColor(getColor(R.color.sl_dim));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(8), pad, dp(8));
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this,
                R.style.Theme_SoundLab_Dialog)
                .setTitle("Rename recording")
                .setView(root)
                .setPositiveButton("Rename", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        new Thread(() -> {
                            libraryDb.renameEntry(entryId, name);
                            runOnUiThread(this::loadEntries);
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        tintDialogButtons(dialog);
    }

    private void duplicateEntry(String entryId) {
        new Thread(() -> {
            try {
                libraryDb.duplicateEntry(entryId);
            } catch (IllegalArgumentException e) {
                runOnUiThread(() -> Toast.makeText(LibraryActivity.this,
                        "Could not duplicate: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                Toast.makeText(LibraryActivity.this, "Duplicated",
                        Toast.LENGTH_SHORT).show();
                loadEntries();
            });
        }).start();
    }

    private void showDeleteDialog(String entryId, String name) {
        AlertDialog dialog = new AlertDialog.Builder(this,
                R.style.Theme_SoundLab_Dialog)
                .setTitle("Delete recording")
                .setMessage("Delete \"" + name
                        + "\" and its history? Exported MP3s are left untouched.")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    libraryDb.deleteEntry(entryId);
                    runOnUiThread(this::loadEntries);
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
        tintDialogButtons(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.sl_red));
    }

    private void tintDialogButtons(AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.sl_teal2));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.sl_mut));
    }

    // ------------------------------------------------------------------
    // RecyclerView
    // ------------------------------------------------------------------

    private static class RowData {
        final LibraryDb.LibraryEntry entry;
        final List<LibraryDb.Op> ops;
        final boolean hasExports;

        RowData(LibraryDb.LibraryEntry entry, List<LibraryDb.Op> ops,
                boolean hasExports) {
            this.entry = entry;
            this.ops = ops;
            this.hasExports = hasExports;
        }
    }

    private class LibraryAdapter extends RecyclerView.Adapter<EntryHolder> {
        private final List<RowData> rows = new ArrayList<>();

        void setRows(List<RowData> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        @Override
        public EntryHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_library_entry, parent, false);
            return new EntryHolder(v);
        }

        @Override
        public void onBindViewHolder(EntryHolder h, int position) {
            h.bind(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private class EntryHolder extends RecyclerView.ViewHolder {
        private final TextView nameView;
        private final TextView subView;
        private final View dotView;
        private final ImageButton overflowBtn;
        private final LinearLayout chipRow;

        EntryHolder(View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.row_name);
            subView = itemView.findViewById(R.id.row_sub);
            dotView = itemView.findViewById(R.id.row_export_dot);
            overflowBtn = itemView.findViewById(R.id.row_overflow);
            chipRow = itemView.findViewById(R.id.row_chips);
        }

        void bind(RowData row) {
            LibraryDb.LibraryEntry e = row.entry;
            nameView.setText(e.name);
            String date = new SimpleDateFormat("MMM d, h:mm a", Locale.US)
                    .format(new Date(e.createdAt));
            long sizeBytes = new File(e.pristinePath).length();
            subView.setText(date + " · " + formatDuration(e.durationS)
                    + " · " + formatBytes(sizeBytes));
            dotView.setVisibility(row.hasExports ? View.VISIBLE : View.GONE);
            bindChips(chipRow, row.ops);
            itemView.setOnClickListener(v -> openEntry(e.id));
            itemView.setOnLongClickListener(v -> {
                showOptionsDialog(e.id, e.name);
                return true;
            });
            overflowBtn.setOnClickListener(v -> showOptionsDialog(e.id, e.name));
        }
    }

    private void bindChips(LinearLayout chipRow, List<LibraryDb.Op> ops) {
        chipRow.removeAllViews();
        if (ops.isEmpty()) {
            chipRow.addView(makeChip("No effects yet", true));
            return;
        }
        int shown = Math.min(ops.size(), MAX_CHIPS);
        for (int i = 0; i < shown; i++) {
            chipRow.addView(makeChip(chipText(ops.get(i).label), false));
        }
        if (ops.size() > MAX_CHIPS) {
            chipRow.addView(makeChip("+" + (ops.size() - MAX_CHIPS), false));
        }
    }

    private TextView makeChip(String text, boolean dashed) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(11);
        chip.setTextColor(getColor(dashed ? R.color.sl_dim : R.color.sl_txt));
        chip.setBackgroundResource(dashed
                ? R.drawable.bg_chip_dashed : R.drawable.bg_chip);
        int hp = dp(10);
        int vp = dp(4);
        chip.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    /** Short chip text derived from an op label. */
    static String chipText(String label) {
        if (label.startsWith("Live FX")) {
            return "Live FX";
        }
        if (label.startsWith("Normalize")) {
            return "Normalize";
        }
        if (label.startsWith("Trim")) {
            return "Trim";
        }
        if (label.startsWith("Join")) {
            return "Join";
        }
        int cut = label.length();
        int comma = label.indexOf(',');
        int middot = label.indexOf('·');
        if (comma >= 0) {
            cut = Math.min(cut, comma);
        }
        if (middot >= 0) {
            cut = Math.min(cut, middot);
        }
        String s = label.substring(0, cut).trim();
        if (s.length() > 18) {
            s = s.substring(0, 18).trim();
        }
        return s.isEmpty() ? label : s;
    }

    /** m:ss, e.g. 73.5s -> "1:14". */
    static String formatDuration(double seconds) {
        long total = Math.round(seconds);
        return (total / 60) + ":"
                + String.format(Locale.US, "%02d", total % 60);
    }

    /** MB with 1 decimal, GB with 2, KB/B below 1 MB. */
    static String formatBytes(long bytes) {
        double gb = 1024.0 * 1024.0 * 1024.0;
        double mb = 1024.0 * 1024.0;
        if (bytes >= gb) {
            return String.format(Locale.US, "%.2f GB", bytes / gb);
        }
        if (bytes >= mb) {
            return String.format(Locale.US, "%.1f MB", bytes / mb);
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
