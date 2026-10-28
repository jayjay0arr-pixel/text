package com.textlauncher.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A launcher that shows nothing but app names. No icons are ever loaded.
 * Type to filter, Enter launches the top match, long-press a name for options.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "text_launcher";
    private static final String KEY_HIDDEN = "hidden";

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF8A8A8A;
    private static final int BG = 0xD9000000; // lets the wallpaper show faintly through

    private static final class AppEntry {
        final String label;
        final ComponentName component;

        AppEntry(String label, ComponentName component) {
            this.label = label;
            this.component = component;
        }

        String key() {
            return component.flattenToString();
        }
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private Set<String> hiddenCache = new HashSet<>();

    private SharedPreferences prefs;
    private ListView list;
    private EditText search;
    private NameAdapter adapter;
    private boolean showHidden;
    private int generation;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Pressing Home while already on the launcher: reset to a clean state. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            search.setText("");
            list.setSelection(0);
            hideKeyboard();
        }
    }

    /** A launcher has nowhere to go back to; Back only clears the search box. */
    @Override
    public void onBackPressed() {
        if (search.getText().length() > 0) {
            search.setText("");
        }
        hideKeyboard();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // Header: clock and date. Long-press toggles showing hidden apps.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(20), dp(24), dp(12));
        header.setOnLongClickListener(v -> {
            showHidden = !showHidden;
            Toast.makeText(this,
                    showHidden ? "Showing hidden apps" : "Hidden apps concealed",
                    Toast.LENGTH_SHORT).show();
            applyFilter();
            return true;
        });

        TextClock time = new TextClock(this);
        time.setTextColor(TEXT);
        time.setTextSize(44);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        header.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(TEXT_DIM);
        date.setTextSize(15);
        header.addView(date);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The app list: names only.
        adapter = new NameAdapter();
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> launch(shown.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showOptions(shown.get(position));
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Search bar at the bottom, within thumb reach.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(4), dp(24), dp(8));

        TextView prompt = new TextView(this);
        prompt.setText("›");
        prompt.setTextColor(TEXT_DIM);
        prompt.setTextSize(24);
        bar.addView(prompt);

        search = new EditText(this);
        search.setBackground(null);
        search.setHint("search");
        search.setHintTextColor(0xFF555555);
        search.setTextColor(TEXT);
        search.setTextSize(18);
        search.setSingleLine(true);
        search.setPadding(dp(10), dp(12), 0, dp(12));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go && !shown.isEmpty()) {
                launch(shown.get(0));
            }
            return go;
        });
        bar.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private final class NameAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = (TextView) convertView;
            if (row == null) {
                row = new TextView(MainActivity.this);
                row.setTextColor(TEXT);
                row.setTextSize(22);
                row.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                row.setSingleLine(true);
                row.setMinHeight(dp(46));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(24), dp(6), dp(24), dp(6));
            }
            AppEntry e = shown.get(position);
            row.setText(e.label);
            row.setAlpha(hiddenCache.contains(e.key()) ? 0.4f : 1f);
            return row;
        }
    }

    // -------------------------------------------------------------- data

    /** Loads app names off the main thread. Icons are never touched. */
    private void reload() {
        final int gen = ++generation;
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            List<AppEntry> out = new ArrayList<>(infos.size());
            for (ResolveInfo ri : infos) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(getPackageName())) {
                    continue;
                }
                out.add(new AppEntry(String.valueOf(ri.loadLabel(pm)),
                        new ComponentName(ai.packageName, ai.name)));
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (gen != generation) {
                    return;
                }
                all.clear();
                all.addAll(out);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        hiddenCache = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));

        shown.clear();
        List<AppEntry> contains = new ArrayList<>();
        for (AppEntry e : all) {
            if (hiddenCache.contains(e.key()) && !showHidden) {
                continue;
            }
            if (q.isEmpty()) {
                shown.add(e);
                continue;
            }
            String l = e.label.toLowerCase(Locale.ROOT);
            if (l.startsWith(q)) {
                shown.add(e);
            } else if (l.contains(q)) {
                contains.add(e);
            }
        }
        shown.addAll(contains); // prefix matches first, then the rest
        adapter.notifyDataSetChanged();
    }

    // ----------------------------------------------------------- actions

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
        hideKeyboard();
        search.setText("");
    }

    private void showOptions(final AppEntry e) {
        final boolean isHidden = hiddenCache.contains(e.key());
        final String[] items = {"App info", "Uninstall", isHidden ? "Unhide" : "Hide"};
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (dialog, which) -> {
                    String pkg = e.component.getPackageName();
                    try {
                        if (which == 0) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + pkg)));
                        } else if (which == 1) {
                            startActivity(new Intent(Intent.ACTION_DELETE,
                                    Uri.parse("package:" + pkg)));
                        } else {
                            toggleHidden(e);
                        }
                    } catch (Exception ex) {
                        Toast.makeText(this, "Not available for this app", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void toggleHidden(AppEntry e) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        if (!set.remove(e.key())) {
            set.add(e.key());
        }
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply();
        applyFilter();
    }

    // ----------------------------------------------------------- helpers

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && search != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
