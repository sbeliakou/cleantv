package com.cleantv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads launchable TV apps and splits them into dock and grid. */
final class AppRepository {

    private static final String DOCK_ORDER = "dock_order";
    private static final Collator COLLATOR = Collator.getInstance(new Locale("be"));

    static final class App {
        String pkg;
        CharSequence label;
        Intent intent;
        Drawable image;
        boolean isBanner;
        int color;
    }

    final List<App> dock = new ArrayList<>();
    final List<App> grid = new ArrayList<>();

    void load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(ctx.getPackageName());
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, hidden, byPkg);

        dock.clear();
        // Once the owner rearranges the dock we follow their order; config.xml is only the default.
        String saved = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(DOCK_ORDER, "");
        String[] order = saved.isEmpty() ? ctx.getResources().getStringArray(R.array.dock_apps)
                : saved.split("\n");
        for (String pkg : order) {
            App a = byPkg.remove(pkg);
            if (a == null) a = phoneApp(pm, pkg);
            if (a != null) dock.add(a);
        }
        grid.clear();
        grid.addAll(byPkg.values());
        grid.sort((x, y) -> COLLATOR.compare(x.label.toString(), y.label.toString()));
    }

    /** Puts an app back into the alphabetical grid and returns where it landed. */
    int insertIntoGrid(App a) {
        int i = 0;
        while (i < grid.size() && COLLATOR.compare(grid.get(i).label.toString(), a.label.toString()) < 0) i++;
        grid.add(i, a);
        return i;
    }

    /** Stores the dock order so it survives a restart. */
    static void saveDockOrder(Context ctx, List<App> dock) {
        StringBuilder sb = new StringBuilder();
        for (App a : dock) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.pkg);
        }
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .edit().putString(DOCK_ORDER, sb.toString()).apply();
    }

    private void collect(PackageManager pm, String category, Set<String> hidden, Map<String, App> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || out.containsKey(ai.packageName)) continue;
            out.put(ai.packageName, build(pm, ai, category));
        }
    }

    /** Dock entry for an app without a TV launcher activity. */
    private App phoneApp(PackageManager pm, String pkg) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
        return r.isEmpty() ? null : build(pm, r.get(0).activityInfo, Intent.CATEGORY_LAUNCHER);
    }

    private App build(PackageManager pm, ActivityInfo ai, String category) {
        App a = new App();
        a.pkg = ai.packageName;
        a.label = ai.loadLabel(pm);
        a.intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setComponent(new ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Drawable banner = ai.loadBanner(pm);
        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        a.isBanner = banner != null;
        a.image = a.isBanner ? banner : ai.loadIcon(pm);
        a.color = sampleColor(a.image);
        return a;
    }

    /** Saturation-weighted average colour of a drawable, brightened for use as a glow. */
    static int sampleColor(Drawable d) {
        int w = 48, h = 27;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, total = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            double weight = hsv[1] * hsv[2] + 0.02;
            r += Color.red(p) * weight;
            g += Color.green(p) * weight;
            bl += Color.blue(p) * weight;
            total += weight;
        }
        if (total == 0) return 0xFF8E8E93;
        int c = Color.rgb((int) (r / total), (int) (g / total), (int) (bl / total));
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(0.85f, hsv[1] * 1.25f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
