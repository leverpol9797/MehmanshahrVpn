package com.firstham.aethergui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * The live log view: a scrolling list of the core's output with per-level filters.
 *
 * <p>Two decisions worth stating, because both are about what happens when the core gets chatty.
 *
 * <p><b>The buffer is bounded.</b> The core's stats reporter emits a line every thirty seconds
 * forever, and a scan on a bad network emits progress lines far faster. Keeping every line would
 * grow the heap without bound for as long as the screen is open, so the oldest lines are dropped
 * once {@link #MAX_LINES} is reached. 2000 lines is roughly an hour of a connected session.
 *
 * <p><b>Follow is off by default.</b> Auto-scrolling fights the reader: someone reading a gateway
 * address who gets yanked to the bottom by the next stats line loses their place. Follow only
 * takes over once the reader has scrolled to the bottom, and releases the moment they scroll up,
 * so turning it on once keeps it on without it hijacking anyone who then scrolls away.
 */
final class LogTerminal {

    /**
     * Cap on retained lines. Sized for a long connected session rather than for the user's
     * patience: at the core's stats cadence an hour of uptime is well inside it.
     */
    static final int MAX_LINES = 2000;

    private final CoreLogParser.Level[] levels = CoreLogParser.Level.values();
    private final List<CoreLogParser.Line> lines = new ArrayList<>();
    private final boolean[] enabled = new boolean[CoreLogParser.Level.values().length];
    private final Adapter adapter;
    private final LinearLayoutManager layoutManager;
    private boolean follow = false;

    LogTerminal(View root) {
        RecyclerView list = root.findViewById(R.id.logList);
        layoutManager = new LinearLayoutManager(root.getContext());
        layoutManager.setStackFromEnd(true);
        adapter = new Adapter();
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);

        // Every level on to begin with. A filter that starts narrow reads as "this log has
        // nothing in it" rather than "you are looking at a slice of it".
        java.util.Arrays.fill(enabled, true);

        root.findViewById(R.id.logClearButton).setOnClickListener(v -> clear());
        root.findViewById(R.id.logEmpty).setVisibility(View.VISIBLE);

        View followToggle = root.findViewById(R.id.logFollowToggle);
        followToggle.setOnClickListener(v -> {
            follow = !follow;
            followToggle.setSelected(follow);
            if (follow) scrollToEnd();
        });
        followToggle.setSelected(false);

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                // Taking over the moment the reader reaches the bottom, and letting go as soon
                // as they leave it, is what stops follow from fighting them for the scroll.
                boolean atBottom = layoutManager.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 1;
                if (follow != atBottom && dy != 0) {
                    follow = atBottom;
                    followToggle.setSelected(follow);
                }
            }
        });

        buildFilterChips(root);
    }

    /**
     * Builds one chip per level plus an All chip. The chips are created in code rather than
     * inflated from a layout because their labels come from resources and their order comes from
     * the enum; inflating eleven near-identical chip blocks would duplicate what is one loop.
     */
    private void buildFilterChips(View root) {
        ChipGroup group = root.findViewById(R.id.logFilterChips);
        Context context = root.getContext();
        group.setOnCheckedStateChangeListener((g, checked) -> {
            // A checked-state change with nothing checked means every chip was turned off; that
            // is a blank screen, so put them all back rather than leaving the user with an
            // empty terminal and no obvious way out.
            if (checked.isEmpty()) {
                for (int i = 0; i < group.getChildCount(); i++)
                    if (group.getChildAt(i) instanceof Chip) ((Chip) group.getChildAt(i)).setChecked(true);
                return;
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (!(child instanceof Chip)) continue;
                Chip chip = (Chip) child;
                int level = chip.getTag() instanceof Integer ? (Integer) chip.getTag() : -1;
                if (level >= 0 && level < enabled.length) enabled[level] = chip.isChecked();
            }
            invalidateVisible();
            adapter.notifyDataSetChanged();
            refreshEmptyState();
        });

        Chip all = new Chip(context);
        all.setText(context.getString(R.string.log_filter_all));
        all.setCheckable(false);
        all.setOnClickListener(v -> {
            java.util.Arrays.fill(enabled, true);
            for (int i = 0; i < group.getChildCount(); i++)
                if (group.getChildAt(i) instanceof Chip) ((Chip) group.getChildAt(i)).setChecked(true);
            invalidateVisible();
            adapter.notifyDataSetChanged();
            refreshEmptyState();
        });
        group.addView(all);

        for (CoreLogParser.Level level : levels) {
            Chip chip = new Chip(context);
            chip.setText(label(context, level));
            chip.setCheckable(true);
            chip.setChecked(true);
            chip.setTag(level.ordinal());
            group.addView(chip);
        }
    }

    private String label(Context context, CoreLogParser.Level level) {
        switch (level) {
            case INFO: return context.getString(R.string.log_level_info);
            case PROGRESS: return context.getString(R.string.log_level_progress);
            case SUCCESS: return context.getString(R.string.log_level_success);
            case WARN: return context.getString(R.string.log_level_warn);
            case ERROR: return context.getString(R.string.log_level_error);
            case META: return context.getString(R.string.log_level_meta);
            default: return level.name();
        }
    }

    /** Appends one raw line from the core, classifies it, and redraws if it is visible. */
    void append(String raw) {
        CoreLogParser.Line parsed = CoreLogParser.parse(raw);
        lines.add(parsed);
        // Drop from the front rather than the back: the newest line is the one being watched.
        while (lines.size() > MAX_LINES) lines.remove(0);
        invalidateVisible();
        if (isVisible(parsed.level)) adapter.notifyItemInserted(lines.size() - 1);
        if (follow) scrollToEnd();
    }

    private boolean isVisible(CoreLogParser.Level level) {
        int index = level.ordinal();
        return index >= 0 && index < enabled.length && enabled[index];
    }

    private void scrollToEnd() {
        if (adapter.getItemCount() == 0) return;
        layoutManager.scrollToPosition(adapter.getItemCount() - 1);
    }

    void clear() {
        lines.clear();
        invalidateVisible();
        adapter.notifyDataSetChanged();
        refreshEmptyState();
    }

    /** Every retained line as plain text, for the copy action. Filters do not apply. */
    String asText() {
        StringBuilder text = new StringBuilder();
        for (CoreLogParser.Line line : lines) {
            if (!line.timestamp.isEmpty()) text.append(line.timestamp).append(' ');
            text.append(line.text).append('\n');
        }
        return text.toString();
    }

    /**
     * Lines after the level filters, cached. Rebuilt only when a line arrives or a chip is
     * toggled, because the adapter asks for this on every bind and on every item count.
     */
    private List<CoreLogParser.Line> visible() {
        if (visibleCache == null || visibleCacheSource != lines.size()) {
            List<CoreLogParser.Line> out = new ArrayList<>();
            for (CoreLogParser.Line line : lines) if (isVisible(line.level)) out.add(line);
            visibleCache = out;
            visibleCacheSource = lines.size();
        }
        return visibleCache;
    }

    private List<CoreLogParser.Line> visibleCache;
    private int visibleCacheSource = -1;

    /** Drops the filtered cache. Called whenever the filters change. */
    private void invalidateVisible() {
        visibleCache = null;
        visibleCacheSource = -1;
    }

    private void refreshEmptyState() {
        View root = layoutManager.findViewById(R.id.logList);
        if (root == null) return;
        boolean nothingLogged = lines.isEmpty();
        ((TextView) ((View) root.getParent()).findViewById(R.id.logEmpty))
                .setVisibility(nothingLogged ? View.VISIBLE : View.GONE);
        ((TextView) ((View) root.getParent()).findViewById(R.id.logFilteredEmpty))
                .setVisibility(nothingLogged ? View.GONE : visible().isEmpty() ? View.VISIBLE : View.GONE);
    }

    @ColorRes
    private static int colourOf(CoreLogParser.Level level) {
        switch (level) {
            case SUCCESS: return R.color.state_connected;
            case ERROR: return R.color.state_error;
            case WARN: return R.color.state_connecting;
            case PROGRESS: return R.color.log_progress;
            case META: return R.color.log_meta;
            default: return R.color.log_info;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @androidx.annotation.NonNull
        @Override public Holder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_line, parent, false);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(@androidx.annotation.NonNull Holder holder, int position) {
            holder.bind(visible().get(position));
        }

        @Override public int getItemCount() {
            return visible().size();
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final TextView text;

        Holder(View view) {
            super(view);
            text = view.findViewById(R.id.logLineText);
            // Monospace so that endpoint addresses line up; a proportional font makes a
            // 162.159.192.79:443 hard to read off the screen at a glance.
            text.setTypeface(Typeface.MONOSPACE);
        }

        void bind(CoreLogParser.Line line) {
            text.setText(line.text);
            text.setTextColor(itemView.getContext().getColor(colourOf(line.level)));
            text.setTypeface(Typeface.MONOSPACE,
                    line.level == CoreLogParser.Level.ERROR ? Typeface.BOLD : Typeface.NORMAL);
        }
    }
}