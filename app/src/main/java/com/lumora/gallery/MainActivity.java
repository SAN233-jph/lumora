package com.lumora.gallery;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static class Photo { long id, date; String name, album; Uri uri; }
    /** type: 0 month title, 1 day title, 2 row of photos, 3 row of albums */
    static class Row { int type, first, n; String text; }
    static class Item { Photo photo; String title; int count; }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Photo> all = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Photo> shown = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<Long, Bitmap> cache =
            new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
                @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount() / 1024; }
            };
    private final ViewOutlineProvider ROUND = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
        }
    };

    private SharedPreferences prefs;
    private Set<String> favs;
    private int tab = 0, cols = 3, index = 0, loadGen = 0, topInset, botInset, sw;
    private int side, gap, cellW, tabW, tabPad;
    private String album = null;
    private float zoom = 1f, downX;
    private boolean viewerOpen, barsVisible = true, favsDirty, hasAccess, dragMoved;
    private int openIndex = -1;
    private float openTx, openTy, openScale = 1f;

    private FrameLayout root, viewer, stage;
    private ListView grid;
    private BaseAdapter adapter;
    private TextView empty, countText, backText, vTitle, heartBtn;
    private GlassPanel countPill, backPill, tabBar, vTop, vBottom;
    private View indicator, stageBg;
    private ImageView photoView;
    private final TextView[] tabs = new TextView[3];

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setNavigationBarContrastEnforced(false);
        prefs = getSharedPreferences("gallery", MODE_PRIVATE);
        favs = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));
        sw = getResources().getDisplayMetrics().widthPixels;
        buildUi();
        if (!prefs.getBoolean("welcomed", false)) showWelcome();
        else askPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hasAccess = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        if (hasAccess) load(); else updateEmpty();
    }

    @Override
    public void onBackPressed() {
        if (viewerOpen) closeViewer();
        else if (tab == 1 && album != null) goBackAlbum();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 99 && res == RESULT_OK) {
            viewerOpen = false;
            viewer.setVisibility(View.GONE);
            photoView.setAlpha(1f);
            load();
        }
    }

    // ---------------------------------------------------------------- UI

    private void askPermission() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1);
        }
    }

    /** First launch: living aurora backdrop, a glass card with the logo, one button. */
    private void showWelcome() {
        final Aurora aurora = new Aurora(this);
        final FrameLayout screen = new FrameLayout(this);
        screen.addView(aurora, new FrameLayout.LayoutParams(MATCH, MATCH));

        final GlassPanel card = new GlassPanel(this, aurora, dp(38));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(20), dp(28), dp(32));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        col.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView title = text(34, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText("Lumora");
        col.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView sub = text(16, 0xB3FFFFFF);
        sub.setGravity(Gravity.CENTER);
        sub.setText("Your photos, beautifully clear.\nEverything stays on your phone.");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
        sl.topMargin = dp(10);
        col.addView(sub, sl);

        final GlassPanel btn = new GlassPanel(this, aurora, dp(28));
        TextView go = text(17, 0xFFFFFFFF);
        go.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        go.setGravity(Gravity.CENTER);
        go.setText("Get started");
        btn.addView(go, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(MATCH, dp(56));
        bl.topMargin = dp(32);
        col.addView(btn, bl);

        card.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        screen.addView(card, new FrameLayout.LayoutParams(sw - dp(48), WRAP, Gravity.CENTER));
        aurora.setOnFrame(() -> {
            card.refresh();
            btn.refresh();
        });

        card.setAlpha(0f);
        card.setTranslationY(dp(40));
        card.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(700)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();

        btn.setOnClickListener(v -> {
            bounce(btn);
            prefs.edit().putBoolean("welcomed", true).apply();
            askPermission();
            screen.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(450)
                    .withEndAction(() -> root.removeView(screen)).start();
        });
        root.addView(screen, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        grid = new ListView(this);
        grid.setDivider(null);
        grid.setDividerHeight(0);
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(android.R.color.transparent);
        adapter = new RowAdapter();
        grid.setAdapter(adapter);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { refreshGlass(); }
        });
        root.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));

        empty = text(17, 0xAAFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(40), 0, dp(40), 0);
        empty.setOnClickListener(v -> {
            if (!hasAccess) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        root.addView(empty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));

        // total photo count (top right)
        countPill = new GlassPanel(this, grid, dp(20));
        countText = text(14, 0xFFFFFFFF);
        countText.setTypeface(Typeface.DEFAULT_BOLD);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(dp(16), 0, dp(16), 0);
        countPill.addView(countText, new FrameLayout.LayoutParams(WRAP, MATCH));
        countPill.setOnClickListener(v -> {
            bounce(countPill);
            grid.smoothScrollToPosition(0);
        });
        root.addView(countPill, new FrameLayout.LayoutParams(WRAP, dp(40), Gravity.TOP | Gravity.END));

        // back button (top left), only visible inside an album
        backPill = new GlassPanel(this, grid, dp(20));
        backText = text(14, 0xFFFFFFFF);
        backText.setTypeface(Typeface.DEFAULT_BOLD);
        backText.setGravity(Gravity.CENTER);
        backText.setSingleLine(true);
        backText.setMaxWidth(sw - dp(160));
        backText.setPadding(dp(16), 0, dp(16), 0);
        backPill.addView(backText, new FrameLayout.LayoutParams(WRAP, MATCH));
        backPill.setOnClickListener(v -> {
            bounce(backPill);
            goBackAlbum();
        });
        backPill.setVisibility(View.GONE);
        root.addView(backPill, new FrameLayout.LayoutParams(WRAP, dp(40), Gravity.TOP | Gravity.START));

        // bottom tab bar with sliding glass indicator
        int barW = sw - dp(56);
        tabPad = dp(6);
        tabW = (barW - 2 * tabPad) / 3;
        tabBar = new GlassPanel(this, grid, dp(34));
        indicator = new View(this);
        GradientDrawable ind = new GradientDrawable();
        ind.setColor(0x38FFFFFF);
        ind.setCornerRadius(dp(28));
        ind.setStroke(dp(1), 0x66FFFFFF);
        indicator.setBackground(ind);
        tabBar.addView(indicator, new FrameLayout.LayoutParams(tabW, dp(56), Gravity.CENTER_VERTICAL));
        indicator.setTranslationX(tabPad);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(tabPad, 0, tabPad, 0);
        String[] names = {"Photos", "Albums", "Favorites"};
        for (int i = 0; i < 3; i++) {
            TextView tv = text(14, 0x99FFFFFF);
            tv.setText(names[i]);
            tv.setGravity(Gravity.CENTER);
            row.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
            tabs[i] = tv;
        }
        tabBar.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH));
        tabBar.setOnTouchListener((v, e) -> onDockTouch(e));
        root.addView(tabBar, new FrameLayout.LayoutParams(barW, dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        styleTabs();

        buildViewer();

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Insets s = ins.getInsets(WindowInsets.Type.systemBars());
            topInset = s.top;
            botInset = s.bottom;
            layoutChrome();
            return ins;
        });
        root.requestApplyInsets();
    }

    private void buildViewer() {
        viewer = new FrameLayout(this);
        viewer.setVisibility(View.GONE);
        stage = new FrameLayout(this);
        stageBg = new View(this);
        stageBg.setBackgroundColor(0xFF000000);
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(stageBg, MATCH, MATCH);
        stage.addView(photoView, MATCH, MATCH);
        viewer.addView(stage, MATCH, MATCH);

        vTop = new GlassPanel(this, stage, dp(26));
        vTitle = text(16, 0xFFFFFFFF);
        vTitle.setTypeface(Typeface.DEFAULT_BOLD);
        vTitle.setSingleLine(true);
        vTitle.setGravity(Gravity.CENTER_VERTICAL);
        vTitle.setPadding(dp(20), 0, dp(20), 0);
        vTop.addView(vTitle, new FrameLayout.LayoutParams(MATCH, MATCH));
        vTop.setOnClickListener(v -> closeViewer());
        viewer.addView(vTop, new FrameLayout.LayoutParams(sw - dp(56), dp(52), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        vBottom = new GlassPanel(this, stage, dp(34));
        LinearLayout actions = new LinearLayout(this);
        heartBtn = text(28, 0xFFFFFFFF);
        TextView share = text(15, 0xFFFFFFFF);
        TextView del = text(15, 0xFFFF6B6B);
        heartBtn.setText("♡");
        share.setText("Share");
        del.setText("Delete");
        heartBtn.setOnClickListener(v -> toggleFav());
        share.setOnClickListener(v -> { bounce(share); sharePhoto(); });
        del.setOnClickListener(v -> { bounce(del); deletePhoto(); });
        for (TextView tv : new TextView[]{heartBtn, share, del}) {
            tv.setGravity(Gravity.CENTER);
            actions.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        vBottom.addView(actions, new FrameLayout.LayoutParams(MATCH, MATCH));
        viewer.addView(vBottom, new FrameLayout.LayoutParams(sw - dp(96), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        final ScaleGestureDetector sgd = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        setZoom(zoom * d.getScaleFactor());
                        return true;
                    }
                });
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleBars(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.05f ? 1f : 2.5f);
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (zoom > 1.05f && !sgd.isInProgress()) {
                    float mx = (zoom - 1f) * root.getWidth() / 2f;
                    float my = (zoom - 1f) * root.getHeight() / 2f;
                    photoView.setTranslationX(Math.max(-mx, Math.min(mx, photoView.getTranslationX() - dx)));
                    photoView.setTranslationY(Math.max(-my, Math.min(my, photoView.getTranslationY() - dy)));
                    refreshViewerGlass();
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (zoom > 1.05f) return false;
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 900) navigate(vx < 0 ? 1 : -1);
                else if (vy > 1400) closeViewer();
                return true;
            }
        });
        stage.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            gd.onTouchEvent(e);
            return true;
        });
        root.addView(viewer, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void layoutChrome() {
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) countPill.getLayoutParams();
        cl.topMargin = topInset + dp(10);
        cl.setMarginEnd(dp(16));
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) backPill.getLayoutParams();
        bl.topMargin = topInset + dp(10);
        bl.setMarginStart(dp(16));
        ((FrameLayout.LayoutParams) vTop.getLayoutParams()).topMargin = topInset + dp(10);
        ((FrameLayout.LayoutParams) tabBar.getLayoutParams()).bottomMargin = botInset + dp(14);
        ((FrameLayout.LayoutParams) vBottom.getLayoutParams()).bottomMargin = botInset + dp(14);
        countPill.requestLayout();
        backPill.requestLayout();
        vTop.requestLayout();
        tabBar.requestLayout();
        vBottom.requestLayout();
        applyGridPadding();
    }

    private void applyGridPadding() {
        grid.setPadding(0, topInset + dp(60), 0, botInset + dp(104));
    }

    // ---------------------------------------------------------------- data

    private void load() {
        pool.execute(() -> {
            List<Photo> out = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    Photo p = new Photo();
                    p.id = c.getLong(0);
                    p.name = c.getString(1);
                    p.album = c.getString(2) == null ? "Other" : c.getString(2);
                    p.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id);
                    long taken = c.getLong(3);
                    p.date = taken > 0 ? taken : c.getLong(4) * 1000L;
                    out.add(p);
                }
            } catch (Exception ignored) { }
            Collections.sort(out, (x, y) -> Long.compare(y.date, x.date));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(out);
                rebuild();
            });
        });
    }

    private boolean isFav(Photo p) {
        return favs.contains(String.valueOf(p.id));
    }

    private void rebuild() {
        items.clear();
        shown.clear();
        rows.clear();
        boolean albums = tab == 1 && album == null;
        if (albums) {
            Map<String, Item> m = new LinkedHashMap<>();
            for (Photo p : all) {
                if (p.album.equalsIgnoreCase("Screenshots")) continue;
                Item it = m.get(p.album);
                if (it == null) {
                    it = new Item();
                    it.photo = p;
                    it.title = p.album;
                    m.put(p.album, it);
                }
                it.count++;
            }
            for (String sp : new String[]{"Recents", "Screenshots", "GIFs"}) {
                Item it = new Item();
                it.title = sp;
                for (Photo p : all) {
                    if (!inCollection(p, sp)) continue;
                    if (it.photo == null) it.photo = p;
                    it.count++;
                }
                if (it.count > 0) items.add(it);
            }
            items.addAll(m.values());
        } else {
            for (Photo p : all) {
                if (tab == 2 && !isFav(p)) continue;
                if (tab == 1 && !inCollection(p, album)) continue;
                shown.add(p);
            }
        }
        cols = albums ? 2 : 3;
        gap = dp(albums ? 14 : 2);
        side = albums ? dp(14) : 0;
        cellW = (sw - 2 * side - (cols - 1) * gap) / cols;
        if (albums) {
            for (int i = 0; i < items.size(); i += cols) addRow(3, i, Math.min(cols, items.size() - i), null);
        } else if (tab == 0) {
            buildDated();
        } else {
            for (int i = 0; i < shown.size(); i += cols) addRow(2, i, Math.min(cols, shown.size() - i), null);
        }
        applyGridPadding();
        adapter.notifyDataSetChanged();
        int total = (tab == 0 || albums) ? all.size() : shown.size();
        countText.setText(String.format(Locale.US, "%,d", total));
        backPill.setVisibility(tab == 1 && album != null ? View.VISIBLE : View.GONE);
        if (album != null) backText.setText("\u2039  " + album);
        updateEmpty();
        refreshGlass();
    }

    /** Automatic collections (Recents, Screenshots, GIFs) and normal folders. */
    private boolean inCollection(Photo p, String c) {
        String name = p.name == null ? "" : p.name.toLowerCase(Locale.ROOT);
        switch (c) {
            case "Recents":
                return System.currentTimeMillis() - p.date < 30L * 24 * 3600 * 1000;
            case "GIFs":
                return name.endsWith(".gif");
            case "Screenshots":
                return p.album.toLowerCase(Locale.ROOT).contains("screenshot") || name.startsWith("screenshot");
            default:
                return p.album.equals(c);
        }
    }

    private void addRow(int type, int first, int n, String text) {
        Row r = new Row();
        r.type = type;
        r.first = first;
        r.n = n;
        r.text = text;
        rows.add(r);
    }

    /** Photos tab: a month title, then a title for each day, then rows of 3 photos (newest first). */
    private void buildDated() {
        SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, d MMM", Locale.getDefault());
        SimpleDateFormat dayYearFmt = new SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault());
        Calendar now = Calendar.getInstance();
        int thisYear = now.get(Calendar.YEAR);
        int today = thisYear * 1000 + now.get(Calendar.DAY_OF_YEAR);
        now.add(Calendar.DAY_OF_YEAR, -1);
        int yesterday = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
        Calendar cal = Calendar.getInstance();
        int lastMonth = -1, lastDay = -1;
        int i = 0;
        while (i < shown.size()) {
            long t = shown.get(i).date;
            cal.setTimeInMillis(t);
            int year = cal.get(Calendar.YEAR);
            int month = year * 100 + cal.get(Calendar.MONTH);
            int day = year * 1000 + cal.get(Calendar.DAY_OF_YEAR);
            if (month != lastMonth) {
                addRow(0, 0, 0, monthFmt.format(new Date(t)));
                lastMonth = month;
            }
            if (day != lastDay) {
                String label;
                if (day == today) label = "Today";
                else if (day == yesterday) label = "Yesterday";
                else label = (year == thisYear ? dayFmt : dayYearFmt).format(new Date(t));
                addRow(1, 0, 0, label);
                lastDay = day;
            }
            int j = i;
            while (j < shown.size() && j - i < cols) {
                cal.setTimeInMillis(shown.get(j).date);
                if (cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) != day) break;
                j++;
            }
            addRow(2, i, j - i, null);
            i = j;
        }
    }

    private void updateEmpty() {
        if (!hasAccess) empty.setText("Photo access is off.\nTap here to open Settings.");
        else if (tab == 2) empty.setText("No favorites yet\nOpen a photo and tap ♥");
        else empty.setText("No photos found");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- tabs

    private int tabAt(float x) {
        int t = (int) ((x - tabPad) / tabW);
        return Math.max(0, Math.min(2, t));
    }

    /** Changes the content of the tab (the glass indicator is moved by the caller). */
    private void switchTab(int t) {
        tab = t;
        album = null;
        styleTabs();
        rebuild();
        grid.setSelection(0);
        popGrid();
        root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Dock: tap a tab, or press and drag the glass blob sideways to change tabs. */
    private boolean onDockTouch(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragMoved = false;
                downX = x;
                indicator.animate().cancel();
                indicator.animate().scaleX(1.12f).scaleY(1.08f).setDuration(160)
                        .setInterpolator(new OvershootInterpolator(2f))
                        .setUpdateListener(a -> refreshGlass()).start();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(x - downX) > dp(8)) dragMoved = true;
                if (!dragMoved) return true;
                float tx = Math.max(tabPad, Math.min(tabPad + 2 * tabW, x - tabW / 2f));
                indicator.animate().cancel();
                indicator.setScaleX(1.12f);
                indicator.setScaleY(1.08f);
                indicator.setTranslationX(tx);
                int hover = tabAt(tx + tabW / 2f);
                if (hover != tab) switchTab(hover);
                refreshGlass();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !dragMoved) {
                    int t = tabAt(x);
                    if (t == tab && album == null) grid.smoothScrollToPosition(0);
                    else switchTab(t);
                }
                indicator.animate().translationX(tabPad + tab * tabW).scaleX(1f).scaleY(1f)
                        .setDuration(460).setInterpolator(new OvershootInterpolator(1.8f))
                        .setUpdateListener(a -> refreshGlass()).start();
                return true;
            default:
                return true;
        }
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            tabs[i].setTextColor(i == tab ? 0xFFFFFFFF : 0x99FFFFFF);
            tabs[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void goBackAlbum() {
        album = null;
        rebuild();
        popGrid();
    }

    private void popGrid() {
        grid.setAlpha(0f);
        grid.setScaleX(0.96f);
        grid.setScaleY(0.96f);
        grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .setUpdateListener(a -> refreshGlass()).start();
    }

    // ---------------------------------------------------------------- grid cells

    private class Cell extends FrameLayout {
        final ImageView img;
        final TextView label, heart;

        Cell(Context ctx) {
            super(ctx);
            img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFF1C1C1E);
            addView(img, new LayoutParams(MATCH, MATCH));
            label = text(15, 0xFFFFFFFF);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.BOTTOM);
            label.setPadding(dp(12), dp(40), dp(12), dp(12));
            label.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xB0000000, 0x00000000}));
            addView(label, new LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
            heart = text(16, 0xFFFF5C7A);
            heart.setText("♥");
            heart.setShadowLayer(6f, 0, 0, 0xFF000000);
            LayoutParams lp = new LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(6), dp(8), 0);
            addView(heart, lp);
        }
    }

    /** One line of the list: up to 3 photo cells (or 2 album cells). */
    private class RowView extends LinearLayout {
        final Cell[] cells = new Cell[3];

        RowView(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            for (int i = 0; i < 3; i++) {
                cells[i] = new Cell(ctx);
                addView(cells[i], new LinearLayout.LayoutParams(1, 1));
            }
        }

        void bind(Row r) {
            boolean albumRow = r.type == 3;
            setLayoutParams(new AbsListView.LayoutParams(MATCH, cellW + gap));
            setPadding(side, 0, side, gap);
            for (int i = 0; i < 3; i++) {
                final Cell c = cells[i];
                if (i >= r.n) {
                    c.setVisibility(View.GONE);
                    continue;
                }
                c.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, cellW);
                lp.rightMargin = i < cols - 1 ? gap : 0;
                c.setLayoutParams(lp);
                c.setOutlineProvider(albumRow ? ROUND : ViewOutlineProvider.BOUNDS);
                c.setClipToOutline(true);
                c.label.setVisibility(albumRow ? View.VISIBLE : View.GONE);
                if (albumRow) {
                    final Item it = items.get(r.first + i);
                    c.label.setText(it.title + "\n" + it.count + (it.count == 1 ? " photo" : " photos"));
                    c.heart.setVisibility(View.GONE);
                    c.setOnClickListener(v -> {
                        album = it.title;
                        rebuild();
                        grid.setSelection(0);
                        popGrid();
                    });
                    loadThumb(it.photo, c.img);
                } else {
                    final int idx = r.first + i;
                    final Photo p = shown.get(idx);
                    c.heart.setVisibility(isFav(p) ? View.VISIBLE : View.GONE);
                    c.setOnClickListener(v -> openViewer(idx, c));
                    loadThumb(p, c.img);
                }
            }
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int pos) { return rows.get(pos).type; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Row r = rows.get(pos);
            if (r.type <= 1) {
                TextView t = cv instanceof TextView ? (TextView) cv : new TextView(MainActivity.this);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                if (r.type == 0) {
                    t.setTextSize(24);
                    t.setTextColor(0xFFFFFFFF);
                    t.setPadding(dp(16), dp(22), dp(16), dp(4));
                } else {
                    t.setTextSize(14);
                    t.setTextColor(0xB3FFFFFF);
                    t.setPadding(dp(16), dp(10), dp(16), dp(8));
                }
                t.setText(r.text);
                return t;
            }
            RowView rv = cv instanceof RowView ? (RowView) cv : new RowView(MainActivity.this);
            rv.bind(r);
            return rv;
        }
    }

    private void loadThumb(Photo p, ImageView iv) {
        iv.animate().cancel();
        iv.setTag(p.id);
        Bitmap cached = cache.get(p.id);
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.setAlpha(1f);
            return;
        }
        iv.setImageDrawable(null);
        iv.setAlpha(0f);
        pool.execute(() -> {
            try {
                Bitmap bm = getContentResolver().loadThumbnail(p.uri, new Size(360, 360), null);
                cache.put(p.id, bm);
                runOnUiThread(() -> {
                    Object t = iv.getTag();
                    if (t != null && (Long) t == p.id) {
                        iv.setImageBitmap(bm);
                        iv.animate().alpha(1f).setDuration(160).setUpdateListener(a -> refreshGlass()).start();
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    // ---------------------------------------------------------------- viewer

    private void openViewer(int pos, View cell) {
        index = pos;
        viewerOpen = true;
        favsDirty = false;
        barsVisible = true;
        viewer.setVisibility(View.VISIBLE);
        showPhoto();

        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        openIndex = pos;
        openScale = cell.getWidth() / (float) root.getWidth();
        openTx = loc[0] + cell.getWidth() / 2f - root.getWidth() / 2f;
        openTy = loc[1] + cell.getHeight() / 2f - root.getHeight() / 2f;

        photoView.setAlpha(1f);
        photoView.setScaleX(openScale);
        photoView.setScaleY(openScale);
        photoView.setTranslationX(openTx);
        photoView.setTranslationY(openTy);
        stageBg.setAlpha(0f);
        stageBg.animate().alpha(1f).setDuration(280).start();
        photoView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(420).setInterpolator(new OvershootInterpolator(0.7f))
                .setUpdateListener(a -> refreshViewerGlass()).start();
        vTop.setAlpha(0f);
        vTop.setTranslationY(-dp(40));
        vBottom.setAlpha(0f);
        vBottom.setTranslationY(dp(40));
        vTop.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
        vBottom.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
    }

    private void closeViewer() {
        if (!viewerOpen) return;
        viewerOpen = false;
        setZoom(1f);
        stageBg.animate().alpha(0f).setDuration(260).start();
        ViewPropertyAnimator a = photoView.animate().setDuration(340)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        if (index == openIndex) {
            a.scaleX(openScale).scaleY(openScale).translationX(openTx).translationY(openTy);
        } else {
            a.scaleX(0.8f).scaleY(0.8f).alpha(0f);
        }
        a.setUpdateListener(x -> refreshViewerGlass()).withEndAction(() -> {
            viewer.setVisibility(View.GONE);
            photoView.setImageDrawable(null);
            photoView.setAlpha(1f);
            if (favsDirty) rebuild();
        }).start();
        vTop.animate().alpha(0f).translationY(-dp(40)).setStartDelay(0).setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        vBottom.animate().alpha(0f).translationY(dp(40)).setStartDelay(0).setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void showPhoto() {
        Photo p = shown.get(index);
        photoView.setImageBitmap(cache.get(p.id));
        setZoom(1f);
        loadFull(p);
        vTitle.setText("‹   " + p.name);
        styleHeart(p);
    }

    private void styleHeart(Photo p) {
        heartBtn.setText(isFav(p) ? "♥" : "♡");
        heartBtn.setTextColor(isFav(p) ? 0xFFFF5C7A : 0xFFFFFFFF);
    }

    /** Full-quality decode. Animated GIF / WebP start playing automatically. */
    private void loadFull(Photo p) {
        final int gen = ++loadGen;
        pool.execute(() -> {
            try {
                Drawable d = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), p.uri),
                        (dec, info, src) -> {
                            if (info.getSize().getWidth() > 4096) dec.setTargetSampleSize(2);
                        });
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    photoView.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                });
            } catch (Exception ignored) { }
        });
    }

    private void navigate(int dir) {
        final int n = index + dir;
        final float w = root.getWidth();
        if (n < 0 || n >= shown.size()) {
            photoView.setTranslationX(-dir * dp(36));
            photoView.animate().translationX(0f).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(3f))
                    .setUpdateListener(a -> refreshViewerGlass()).start();
            return;
        }
        photoView.animate().translationX(-dir * w * 0.35f).alpha(0f).setDuration(140)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setUpdateListener(a -> refreshViewerGlass())
                .withEndAction(() -> {
                    index = n;
                    showPhoto();
                    photoView.setTranslationX(dir * w * 0.35f);
                    photoView.animate().translationX(0f).alpha(1f).setDuration(380)
                            .setInterpolator(new OvershootInterpolator(1.1f))
                            .setUpdateListener(a -> refreshViewerGlass()).start();
                }).start();
    }

    private void setZoom(float z) {
        zoom = Math.max(1f, Math.min(5f, z));
        photoView.setScaleX(zoom);
        photoView.setScaleY(zoom);
        if (zoom <= 1.0f) {
            photoView.setTranslationX(0f);
            photoView.setTranslationY(0f);
        }
        refreshViewerGlass();
    }

    private void animateZoom(float target) {
        ValueAnimator va = ValueAnimator.ofFloat(zoom, target);
        va.setDuration(280);
        va.setInterpolator(new AccelerateDecelerateInterpolator());
        va.addUpdateListener(a -> setZoom((Float) a.getAnimatedValue()));
        va.start();
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        float a = barsVisible ? 1f : 0f;
        vTop.animate().alpha(a).translationY(barsVisible ? 0 : -dp(30)).setStartDelay(0).setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        vBottom.animate().alpha(a).translationY(barsVisible ? 0 : dp(30)).setStartDelay(0).setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void toggleFav() {
        Photo p = shown.get(index);
        String k = String.valueOf(p.id);
        if (!favs.remove(k)) favs.add(k);
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        favsDirty = true;
        styleHeart(p);
        bounce(heartBtn);
        heartBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        refreshViewerGlass();
    }

    private void sharePhoto() {
        Photo p = shown.get(index);
        Intent i = new Intent(Intent.ACTION_SEND);
        String type = getContentResolver().getType(p.uri);
        i.setType(type == null ? "image/*" : type);
        i.putExtra(Intent.EXTRA_STREAM, p.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void deletePhoto() {
        Photo p = shown.get(index);
        try {
            IntentSender sender = MediaStore.createDeleteRequest(getContentResolver(),
                    Collections.singletonList(p.uri)).getIntentSender();
            startIntentSenderForResult(sender, 99, null, 0, 0, 0);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private void refreshGlass() {
        if (countPill != null) countPill.refresh();
        if (backPill != null) backPill.refresh();
        if (tabBar != null) tabBar.refresh();
    }

    private void refreshViewerGlass() {
        if (vTop != null) vTop.refresh();
        if (vBottom != null) vBottom.refresh();
    }

    private void bounce(View v) {
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
