package fr.portal.accueil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Portal's home screen. Being the home screen is what keeps "Alexa" working: Android 10
 * only lets the app in front use the mic, and this screen is in front almost all the time.
 */
public class HomeActivity extends Activity {
    // Read by WakeService to decide whether to come back here after an Alexa turn.
    static volatile boolean inFront;

    private static final String CITY = "Reims";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=49.2583&longitude=4.0317&timezone=Europe%2FParis&forecast_days=4"
            + "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000;
    private static final int COLUMNS = 4;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_SOFT = 0xFFB8C7E0;
    private static final int CARD = 0x26FFFFFF;
    private static final int ACCENT = 0xFF00CAFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView clock, date, listen, weatherNow, weatherText;
    private View listenDot;
    private LinearLayout forecast, grid;
    private TextView appsTitle;
    // App being moved by hand (package/activity), and the order currently on screen.
    private String moving;
    private final List<String> shown = new ArrayList<>();
    private ScrollView scroll;
    private int dragY = -1; // finger height on screen while dragging an app, -1 otherwise
    private long weatherAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("accueil", MODE_PRIVATE);
        setContentView(build());
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeService.start(this);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        showWeather(prefs.getString("weather", null));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) WakeService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        WakeService.requestReopen();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        loadApps();
        ui.post(tick);
        if (System.currentTimeMillis() - weatherAt > WEATHER_EVERY_MS) fetchWeather();
    }

    @Override
    protected void onPause() {
        inFront = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to, except leaving the move mode.
        if (moving != null) {
            moving = null;
            loadApps();
        }
    }

    // ---- layout ----

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A1630, 0xFF14306B, 0xFF3A1D6E}));
        root.setPadding(dp(44), dp(32), dp(36), dp(32));

        // Left: listening state, clock, weather, Alexa button.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 0.72f));

        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        listenDot = new View(this);
        listenDot.setBackground(round(ACCENT, 6));
        chip.addView(listenDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        listen = text("", 21, TEXT_SOFT);
        listen.setPadding(dp(10), 0, 0, 0);
        chip.addView(listen);
        left.addView(chip);

        clock = text("", 132, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(18);
        left.addView(clock, cp);
        clock.setOnLongClickListener(v -> {
            menu();
            return true;
        });

        date = text("", 30, TEXT_SOFT);
        left.addView(date);

        LinearLayout weather = new LinearLayout(this);
        weather.setOrientation(LinearLayout.VERTICAL);
        weather.setBackground(round(CARD, 22));
        weather.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(26);
        left.addView(weather, wp);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        weatherNow = text("--°", 58, TEXT);
        weatherNow.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        now.addView(weatherNow);
        weatherText = text(CITY, 22, TEXT_SOFT);
        weatherText.setPadding(dp(16), 0, 0, 0);
        now.addView(weatherText);
        weather.addView(now);

        forecast = new LinearLayout(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(10);
        weather.addView(forecast, fp);

        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        TextView alexa = text("◉   Parler à Alexa", 26, TEXT);
        alexa.setGravity(Gravity.CENTER);
        alexa.setBackground(round(0xFF0866FF, 32));
        alexa.setPadding(dp(34), dp(18), dp(34), dp(18));
        alexa.setOnClickListener(v -> WakeService.triggerNow(this));
        left.addView(alexa, new LinearLayout.LayoutParams(-2, -2));

        // Right: apps.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(round(0x1AFFFFFF, 28));
        right.setPadding(dp(20), dp(18), dp(20), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1.28f);
        rp.leftMargin = dp(28);
        root.addView(right, rp);

        appsTitle = text("Applications", 22, TEXT_SOFT);
        appsTitle.setPadding(dp(6), 0, 0, dp(12));
        right.addView(appsTitle);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // Gaps between cells and the title still report where the dragged app is.
        scroll.setOnDragListener((v, e) -> trackDrag(v, e));
        appsTitle.setOnDragListener((v, e) -> trackDrag(v, e));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        right.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.FRANCE).format(now));
            String d = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now);
            date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            boolean ok = WakeService.running && "à l'écoute".equals(WakeService.status);
            listen.setText(ok ? "Dites « Alexa »" : WakeService.status);
            listenDot.setBackground(round(ok ? ACCENT : 0xFFFFB020, 6));
            ui.postDelayed(this, 1000);
        }
    };

    // ---- apps ----

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
        Collections.sort(all, new ResolveInfo.DisplayNameComparator(pm));
        // Apps placed by hand come first, in their order; new ones follow alphabetically.
        List<String> order = savedOrder();
        Collections.sort(all, (a, b) -> {
            int ia = order.indexOf(key(a)), ib = order.indexOf(key(b));
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return Integer.compare(ia, ib);
        });
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        grid.removeAllViews();
        shown.clear();
        appsTitle.setText(moving == null ? "Applications" : "Touchez la nouvelle place de l'appli");
        appsTitle.setTextColor(moving == null ? TEXT_SOFT : ACCENT);
        LinearLayout row = null;
        int n = 0;
        for (ResolveInfo info : all) {
            String pkg = info.activityInfo.packageName;
            // Hidden entries are per icon (package/activity): one app can have several icons.
            if (pkg.equals(getPackageName()) || hidden.contains(key(info))) continue;
            shown.add(key(info));
            if (n % COLUMNS == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(-1, dp(146)));
            }
            row.addView(appCell(pm, info), new LinearLayout.LayoutParams(0, -1, 1));
            n++;
        }
        // Keep the last row's cells the same width as the others.
        while (n % COLUMNS != 0 && row != null) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
            n++;
        }
    }

    private static String key(ResolveInfo info) {
        return info.activityInfo.packageName + "/" + info.activityInfo.name;
    }

    private List<String> savedOrder() {
        String s = prefs.getString("order", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    /** Puts the app being moved in front of {@code target}, or last when target is null. */
    private void moveTo(String target) {
        List<String> order = new ArrayList<>(shown);
        order.remove(moving);
        int at = target == null ? -1 : order.indexOf(target);
        if (at < 0) order.add(moving);
        else order.add(at, moving);
        prefs.edit().putString("order", TextUtils.join("\n", order)).apply();
        moving = null;
        loadApps();
    }

    private View appCell(PackageManager pm, ResolveInfo info) {
        String pkg = info.activityInfo.packageName;
        String key = key(info);
        String label = prefs.getString("name:" + key, info.loadLabel(pm).toString());
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(6), dp(4), dp(6));
        cell.setBackground(round(key.equals(moving) ? 0x5500CAFF : 0x00000000, 18));
        int custom = prefs.getInt("icon:" + key, -1);
        if (custom >= 0 && custom < ICONS.length) {
            cell.addView(iconTile(custom, 40), new LinearLayout.LayoutParams(dp(76), dp(76)));
        } else {
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(info.loadIcon(pm));
            } catch (RuntimeException ignored) {
            }
            cell.addView(icon, new LinearLayout.LayoutParams(dp(76), dp(76)));
        }
        TextView name = text(label, 17, TEXT);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        cell.addView(name, np);
        cell.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(i);
            } catch (RuntimeException ignored) {
            }
        });
        // Long press picks the app up: drop it on another app to take that place, or let go
        // where it was to get the menu.
        cell.setOnLongClickListener(v -> {
            moving = key;
            appsTitle.setText("Glissez l'appli à sa nouvelle place");
            appsTitle.setTextColor(ACCENT);
            v.startDragAndDrop(null, new View.DragShadowBuilder(v), key, 0);
            dragY = -1;
            ui.post(autoScroll);
            v.setAlpha(0.3f);
            return true;
        });
        cell.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    return trackDrag(v, e);
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (!key.equals(moving)) v.setBackground(round(0x5500CAFF, 18));
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.setBackground(round(0x00000000, 18));
                    return true;
                case DragEvent.ACTION_DROP:
                    v.setBackground(round(0x00000000, 18));
                    // Rebuild the grid after the drag finishes, not while it dispatches.
                    if (key.equals(moving)) ui.post(() -> appMenu(pkg, key, label));
                    else ui.post(() -> moveTo(key));
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    dragY = -1;
                    ui.removeCallbacks(autoScroll);
                    if (key.equals(moving) && !e.getResult()) ui.post(() -> {
                        moving = null;
                        loadApps();
                    });
                    return true;
                default:
                    return true;
            }
        });
        return cell;
    }

    private boolean trackDrag(View v, DragEvent e) {
        if (e.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            dragY = at[1] + (int) e.getY();
        }
        return true;
    }

    /** While an app is dragged near the top or bottom of the list, scroll the list that way. */
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (moving == null) return;
            if (dragY >= 0) {
                int[] at = new int[2];
                scroll.getLocationOnScreen(at);
                int edge = dp(90);
                if (dragY < at[1] + edge) scroll.scrollBy(0, -dp(14));
                else if (dragY > at[1] + scroll.getHeight() - edge) scroll.scrollBy(0, dp(14));
            }
            ui.postDelayed(this, 16);
        }
    };

    private void appMenu(String pkg, String key, String label) {
        moving = null;
        loadApps();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(label)
                    .setItems(removable(pkg)
                            ? new String[]{"Renommer", "Mettre au début", "Mettre à la fin",
                                    "Masquer de l'accueil", "Changer l'icône", "Infos de l'appli", "Désinstaller"}
                            : new String[]{"Renommer", "Mettre au début", "Mettre à la fin",
                                    "Masquer de l'accueil", "Changer l'icône", "Infos de l'appli"}, (d, which) -> {
                        if (which == 6) {
                            confirmUninstall(pkg, label);
                            return;
                        }
                        if (which == 0) {
                            rename(key, label);
                        } else if (which == 1) {
                            if (!shown.isEmpty() && !shown.get(0).equals(key)) {
                                moving = key;
                                moveTo(shown.get(0));
                            }
                        } else if (which == 2) {
                            moving = key;
                            moveTo(null);
                        } else if (which == 3) {
                            Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
                            h.add(key);
                            prefs.edit().putStringSet("hidden", h).apply();
                            loadApps();
                        } else if (which == 4) {
                            pickIcon(key, label);
                        } else {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    })
                    .show();
    }

    /** Apps the user installed; Meta's system apps can't be removed this way. */
    private boolean removable(String pkg) {
        try {
            int flags = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Asks first: Immortal's accessibility service may tap through Android's own confirmation,
     * so this dialog can be the only safeguard.
     */
    private void confirmUninstall(String pkg, String label) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Désinstaller « " + label + " » ?")
                .setMessage("L'appli et ses données seront supprimées du Portal.")
                .setPositiveButton("Désinstaller", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // Replacement icons for apps whose own icon is ugly or generic (Meta's lab flask): symbol,
    // name, tile color.
    private static final Object[][] ICONS = {
            {"⚙️", "Réglages", 0xFF607D8B}, {"📷", "Caméra", 0xFF1E88E5}, {"🖼️", "Photos", 0xFFD81B60},
            {"🏠", "Maison", 0xFF43A047}, {"📺", "Télé", 0xFF8E24AA}, {"🎬", "Films", 0xFFE53935},
            {"🎵", "Musique", 0xFFFB8C00}, {"🌐", "Internet", 0xFF039BE5}, {"🛒", "Boutique", 0xFF7CB342},
            {"💬", "Messages", 0xFF00ACC1}, {"📞", "Appels", 0xFF2E7D32}, {"📅", "Agenda", 0xFF3949AB},
            {"🔧", "Outils", 0xFF546E7A}, {"🎮", "Jeux", 0xFF5E35B1}, {"📁", "Fichiers", 0xFFFFB300},
            {"🔒", "Sécurité", 0xFF37474F}, {"⭐", "Favori", 0xFFF4511E}, {"🧪", "Labo", 0xFF6D4C41},
    };

    private View iconTile(int i, int sp) {
        TextView t = text((String) ICONS[i][0], sp, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        int c = (Integer) ICONS[i][2];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{c, (c & 0x00FFFFFF) | 0xAA000000});
        g.setCornerRadius(dp(20));
        t.setBackground(g);
        return t;
    }

    private void pickIcon(String key, String label) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout row = null;
        for (int i = 0; i < ICONS.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                list.addView(row);
            }
            LinearLayout opt = new LinearLayout(this);
            opt.setOrientation(LinearLayout.VERTICAL);
            opt.setGravity(Gravity.CENTER);
            opt.setPadding(dp(8), dp(8), dp(8), dp(8));
            opt.addView(iconTile(i, 30), new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView n = text((String) ICONS[i][1], 13, TEXT_SOFT);
            n.setGravity(Gravity.CENTER);
            opt.addView(n);
            int pick = i;
            opt.setOnClickListener(v -> {
                prefs.edit().putInt("icon:" + key, pick).apply();
                dialog[0].dismiss();
                loadApps();
            });
            row.addView(opt, new LinearLayout.LayoutParams(dp(96), -2));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        dialog[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Icône de « " + label + " »")
                .setView(sv)
                .setNeutralButton("Icône d'origine", (d, w) -> {
                    prefs.edit().remove("icon:" + key).apply();
                    loadApps();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void rename(String key, String label) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(label);
        input.setSelectAllOnFocus(true);
        input.setTextSize(20);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Renommer")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (s.isEmpty()) prefs.edit().remove("name:" + key).apply();
                    else prefs.edit().putString("name:" + key, s).apply();
                    loadApps();
                })
                .setNeutralButton("Nom d'origine", (d, w) -> {
                    prefs.edit().remove("name:" + key).apply();
                    loadApps();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Long press on the clock: bring back hidden apps, or open the listening status page. */
    private void menu() {
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Portal Accueil")
                .setItems(new String[]{"Réafficher les applis masquées (" + hidden.size() + ")",
                        "Remettre l'ordre alphabétique", "État de l'écoute « Alexa »", "Paramètres Android"},
                (d, which) -> {
                    if (which == 0) {
                        prefs.edit().remove("hidden").apply();
                        loadApps();
                    } else if (which == 1) {
                        prefs.edit().remove("order").apply();
                        loadApps();
                    } else if (which == 2) {
                        startActivity(new Intent(this, MainActivity.class));
                    } else {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                })
                .show();
    }

    // ---- weather (Open-Meteo, no key) ----

    private void fetchWeather() {
        weatherAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(WEATHER_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) > 0) sb.append(new String(buf, 0, r, "UTF-8"));
                }
                String json = sb.toString();
                prefs.edit().putString("weather", json).apply();
                ui.post(() -> showWeather(json));
            } catch (Exception e) {
                weatherAt = 0; // retry on next resume
            }
        }, "meteo").start();
    }

    private void showWeather(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            weatherNow.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            int code = cur.getInt("weather_code");
            weatherText.setText(icon(code) + "  " + label(code) + "\n" + CITY);

            JSONObject daily = o.getJSONObject("daily");
            JSONArray days = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray max = daily.getJSONArray("temperature_2m_max");
            JSONArray min = daily.getJSONArray("temperature_2m_min");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.FRANCE);
            forecast.removeAllViews();
            for (int i = 0; i < days.length(); i++) {
                String day = i == 0 ? "Auj." : out.format(in.parse(days.getString(i)));
                day = Character.toUpperCase(day.charAt(0)) + day.substring(1);
                TextView t = text(day + "\n" + icon(codes.getInt(i)) + "\n"
                        + Math.round(max.getDouble(i)) + "° / " + Math.round(min.getDouble(i)) + "°", 17, TEXT_SOFT);
                t.setGravity(Gravity.CENTER);
                t.setLineSpacing(dp(2), 1);
                forecast.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            }
        } catch (Exception ignored) {
        }
    }

    private static String icon(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "🌤️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 67 || (code >= 80 && code <= 82)) return "🌧️";
        if (code <= 77 || code == 85 || code == 86) return "❄️";
        return "⛈️";
    }

    private static String label(int code) {
        if (code == 0) return "Ensoleillé";
        if (code <= 2) return "Éclaircies";
        if (code == 3) return "Couvert";
        if (code <= 48) return "Brouillard";
        if (code <= 57) return "Bruine";
        if (code <= 67) return "Pluie";
        if (code <= 77) return "Neige";
        if (code <= 82) return "Averses";
        if (code <= 86) return "Averses de neige";
        return "Orage";
    }

    // ---- helpers ----

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
