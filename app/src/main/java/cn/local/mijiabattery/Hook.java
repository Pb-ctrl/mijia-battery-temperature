package cn.local.mijiabattery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Hook implements IXposedHookLoadPackage {
    private static final String MI = "com.xiaomi.smarthome";
    private static final String SC = "com.miui.securitycenter";
    private static final String BATTERY = "key_battery_condition_item";
    private static final String ADD = MI + ".autotask.activity.AddConditionActivity";
    private static final String ROW_TAG = "mijia-battery-temperature-row-v1";
    private static volatile Context context;
    private static boolean installed;
    private static final Map<String, Integer> baselines = new HashMap<>();
    private static final ThreadLocal<Boolean> dispatchChange = new ThreadLocal<>();

    private static void log(String s) { XposedBridge.log("[MijiaBattery] " + s); }
    private static boolean enabled() {
        return currentSample() != null;
    }
    private static Sample currentSample() {
        if (context == null) return null;
        try { return Sample.read(context.getContentResolver()); }
        catch (RuntimeException ignored) { return null; }
    }
    private static int temperature() {
        Sample sample = currentSample();
        return sample == null ? Integer.MIN_VALUE : sample.temperature;
    }
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!MI.equals(lp.packageName) && !SC.equals(lp.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (installed) return;
                Context c = (Context) p.args[0];
                try {
                    PackageInfo info = c.getPackageManager().getPackageInfo(lp.packageName, 0);
                    long expected = MI.equals(lp.packageName) ? 11011845L : 40001381L;
                    if (info.getLongVersionCode() != expected) { log("Version unsupported: " + lp.packageName); return; }
                    installed = true; context = c.getApplicationContext();
                    if (context == null) context = c;
                    if (MI.equals(lp.packageName)) installMijia(lp.classLoader);
                    else installSecurity(lp.classLoader);
                    log("Hooks ready: " + lp.packageName);
                } catch (Throwable t) { log("Hook setup failed: " + t.getClass().getSimpleName()); }
            }
        });
    }
    private static JSONObject edit(Activity activity) {
        try {
            Bundle b = activity.getIntent().getBundleExtra("argument_app_config");
            if (b == null) return null;
            b.setClassLoader(activity.getClassLoader());
            Object condition = b.getParcelable("argument_edit_condition_obj");
            if (condition == null) return null;
            String json = (String) XposedHelpers.callMethod(condition, "getJsonStr");
            JSONObject j = new JSONObject(json);
            return Logic.marked(j.optString("instanceId")) ? j : null;
        } catch (Throwable ignored) { return null; }
    }
    private static void installMijia(ClassLoader loader) {
        Class<?> add = XposedHelpers.findClass(ADD, loader);
        XposedHelpers.findAndHookMethod(add, "onCreate", Bundle.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || !enabled()) return;
                final Activity a = (Activity) p.thisObject;
                a.getWindow().getDecorView().post(() -> {
                    try { if (!a.isFinishing()) injectRow(a); }
                    catch (Throwable t) { log("Menu insertion failed: " + t.getClass().getSimpleName()); }
                });
            }
        });
        XposedHelpers.findAndHookMethod(add, "OooOOoo", boolean.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                JSONObject j = edit(a);
                if (j == null) return;
                p.setResult(null);
                if (enabled()) dialog(a, j);
                else new AlertDialog.Builder(a).setTitle("电池温度扩展未启动").setMessage("请启用配套 KSU 模块并重启后再编辑。").setPositiveButton("确定", null).show();
            }
        });
        // The wire format stays compatible with the existing phone property, while the
        // persisted local value is deliberately outside 0..100 to fail closed without hooks.
        XposedHelpers.findAndHookMethod("_m_j.uc7", loader, "OooOoO", String.class, String.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!BATTERY.equals(p.args[0])) return;
                try {
                    JSONObject j = new JSONObject((String) p.args[1]);
                    if (!Logic.marked(j.optString("instanceId"))) return;
                    int[] v = values(j);
                    p.setResult(Logic.valid(v) ? Integer.valueOf(v[1] - Logic.OFFSET) : "");
                } catch (Throwable t) { log("Temperature wire conversion failed"); }
            }
        });
    }
    private static TextView findLabel(View v) {
        if (v instanceof TextView) {
            String s = ((TextView) v).getText().toString();
            if (s.equals("电量使用") || s.equals("Battery usage")) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { TextView t = findLabel(g.getChildAt(i)); if (t != null) return t; }
        }
        return null;
    }
    private static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    private static void injectRow(Activity a) {
        View root = a.getWindow().getDecorView();
        if (root.findViewWithTag(ROW_TAG) != null) return;
        TextView original = findLabel(root);
        if (original == null) { log("Battery menu label not found"); return; }
        View card = original;
        LinearLayout parent = null;
        for (int i = 0; i < 7; i++) {
            ViewParent p = card.getParent();
            if (p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL
                && ((LinearLayout) p).getChildCount() >= 3) { parent = (LinearLayout) p; break; }
            if (!(p instanceof View)) break;
            card = (View) p;
        }
        if (parent == null) { log("Unsupported menu layout; nothing changed"); return; }
        LinearLayout row = new LinearLayout(a); row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 24), dp(a, 20), dp(a, 24), dp(a, 20));
        if (card.getBackground() != null && card.getBackground().getConstantState() != null)
            row.setBackground(card.getBackground().getConstantState().newDrawable());
        else { GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(30, 30, 30)); bg.setCornerRadius(dp(a, 24)); row.setBackground(bg); }
        TextView icon = new TextView(a); icon.setText("🌡"); icon.setTextSize(26);
        row.addView(icon, new LinearLayout.LayoutParams(dp(a, 48), -2));
        TextView label = new TextView(a); label.setText("电池温度变化"); label.setTextSize(20); label.setTextColor(original.getCurrentTextColor());
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = new TextView(a); arrow.setText("›"); arrow.setTextColor(Color.GRAY); arrow.setTextSize(28); row.addView(arrow);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        if (card.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams old = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            layout.setMargins(old.leftMargin, old.topMargin, old.rightMargin, old.bottomMargin);
        }
        row.setOnClickListener(v -> {
            if (!enabled()) { Toast.makeText(a, "请先启用配套 KSU 模块", Toast.LENGTH_LONG).show(); return; }
            try {
                Bundle b = a.getIntent().getBundleExtra("argument_app_config");
                ArrayList<String> disabled = b == null ? null : b.getStringArrayList("argument_disable_options");
                if (disabled != null && disabled.contains(BATTERY)) {
                    Toast.makeText(a, "同一条规则的温度与电量条件不能并用，请先移除已有条件", Toast.LENGTH_LONG).show(); return;
                }
                dialog(a, null);
            } catch (Throwable t) { log("Dialog failed: " + t.getClass().getSimpleName()); }
        });
        parent.addView(row, parent.indexOfChild(card) + 1, layout);
        log("Temperature menu inserted");
    }
    private static int[] values(JSONObject j) {
        JSONArray a = j.optJSONArray("c");
        if (a == null || a.length() != 3) return null;
        return new int[]{a.optInt(0, -1), a.optInt(1, -1), a.optInt(2, -1)};
    }
    private static void dialog(final Activity a, final JSONObject previous) {
        int[] old = previous == null ? null : values(previous);
        int mode = Logic.valid(old) ? old[0] : 2;
        int threshold = Logic.valid(old) ? old[1] - Logic.OFFSET : 35;
        LinearLayout content = new LinearLayout(a); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(a, 24), dp(a, 8), dp(a, 24), dp(a, 8));
        TextView reading = new TextView(a); int t = temperature();
        reading.setText(t == Integer.MIN_VALUE ? "尚未取得有效电池温度" : "当前电池温度：" + Logic.display(t)); content.addView(reading);
        RadioGroup choices = new RadioGroup(a);
        String[] names = {"低于指定温度时", "高于指定温度时", "电池温度发生变化时"};
        for (int i = 0; i < names.length; i++) { RadioButton r = new RadioButton(a); r.setId(5000 + i); r.setText(names[i]); choices.addView(r); }
        choices.check(5000 + mode); content.addView(choices);
        EditText degrees = new EditText(a); degrees.setInputType(InputType.TYPE_CLASS_NUMBER); degrees.setHint("温度（1～80℃）"); degrees.setText(String.valueOf(threshold)); degrees.setEnabled(mode != 2); content.addView(degrees);
        choices.setOnCheckedChangeListener((g, id) -> degrees.setEnabled(id != 5002));
        TextView note = new TextView(a); note.setText("约5秒采样一次。首次采样只建立基线；高／低温条件仅在跨过阈值时触发。执行动作在米家中自行设置。"); note.setTextSize(13); content.addView(note);
        AlertDialog d = new AlertDialog.Builder(a).setTitle("电池温度变化").setView(content).setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        d.setOnShowListener(ignored -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int selected = choices.getCheckedRadioButtonId() - 5000;
                int value = selected == 2 ? 35 : Integer.parseInt(degrees.getText().toString().trim());
                int encoded = Logic.encode(value);
                Bundle config = a.getIntent().getBundleExtra("argument_app_config");
                String user = config == null ? null : config.getString("argument_user_id");
                if (user == null || user.isEmpty()) throw new IllegalStateException("当前米家账号信息不可用");
                JSONObject j = previous == null ? new JSONObject() : new JSONObject(previous.toString());
                String id = j.optString("instanceId");
                if (!Logic.marked(id)) id = Logic.PREFIX + UUID.randomUUID().toString().substring(9);
                int[] vals = new int[]{selected, encoded, encoded};
                j.put("instanceId", id); j.put("taskChannel", "miHome"); j.put("b", 0); j.put("c", new JSONArray(vals));
                Bundle result = new Bundle();
                result.putString("argument_type_id", BATTERY); result.putString("argument_instance_id", id); result.putString("argument_user_id", user);
                result.putString("argument_condition_name", "电池温度变化"); result.putString("argument_condition_summary", Logic.summary(vals));
                result.putString("argument_condition_sync_json", j.toString());
                a.setResult(Activity.RESULT_OK, new Intent().putExtra("argument_create_result", result)); d.dismiss(); a.finish();
                log("Temperature condition selected");
            } catch (NumberFormatException e) { degrees.setError("请输入1～80的整数温度"); }
            catch (IllegalArgumentException e) { degrees.setError("温度范围为1～80℃"); }
            catch (Throwable e) { Toast.makeText(a, "保存失败：" + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); log("Condition result failed"); }
        }));
        d.show();
    }
    private static boolean markedItem(Object item) {
        try { return Logic.marked((String) XposedHelpers.callMethod(item, "d")); }
        catch (Throwable ignored) { return false; }
    }
    private static int[] itemValues(Object item) {
        try { return (int[]) XposedHelpers.callMethod(item, "u"); }
        catch (Throwable ignored) { return null; }
    }
    private static void installSecurity(final ClassLoader loader) {
        final Class<?> battery = XposedHelpers.findClass("com.miui.autotask.taskitem.BatteryConditionItem", loader);
        final Class<?> manager = XposedHelpers.findClass("g6.n", loader);
        XposedHelpers.findAndHookMethod(battery, "m", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!markedItem(p.thisObject)) return;
                int[] v = itemValues(p.thisObject); int t = temperature();
                boolean result = Logic.satisfied(v, t);
                if (result && v[0] == 2) result = Boolean.TRUE.equals(dispatchChange.get());
                p.setResult(result);
            }
        });
        for (String name : new String[]{"g", "h"}) {
            final String method = name;
            XposedHelpers.findAndHookMethod(battery, name, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (markedItem(p.thisObject)) p.setResult(method.equals("h") ? "电池温度变化" : Logic.summary(itemValues(p.thisObject)));
                }
            });
        }
        // Native battery percentage checks must never dispatch temperature items.
        XposedHelpers.findAndHookMethod(manager, "R0", ConcurrentHashMap.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                ConcurrentHashMap<Object, Object> filtered = new ConcurrentHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) p.args[0]).entrySet())
                    if (!markedItem(entry.getValue())) filtered.put(entry.getKey(), entry.getValue());
                p.args[0] = filtered;
            }
        });
        final Handler events = new Handler(Looper.getMainLooper());
        final class Evaluator {
            private Sample previous;

            void evaluate(boolean rulesChanged) {
                try {
                    Sample sample = currentSample();
                    Sample beforeSample = previous;
                    previous = sample;
                    if (sample == null) { baselines.clear(); }
                    else {
                        boolean newSession = beforeSample == null || !beforeSample.epoch.equals(sample.epoch)
                                || sample.timestamp - beforeSample.timestamp >= 45;
                        if (newSession) baselines.clear();
                        if (!newSession && !rulesChanged && beforeSample.temperature == sample.temperature) return;
                        int now = sample.temperature;
                        Object engine = XposedHelpers.callStaticMethod(manager, "G0");
                        Map<?, ?> items = (Map<?, ?>) XposedHelpers.getObjectField(engine, "z");
                        java.util.HashSet<String> alive = new java.util.HashSet<>();
                        for (Object item : items.values()) {
                            if (!battery.isInstance(item) || !markedItem(item)) continue;
                            String id = (String) XposedHelpers.callMethod(item, "d");
                            int[] vals = itemValues(item);
                            if (!Logic.valid(vals)) continue;
                            String stateKey = String.valueOf(XposedHelpers.callMethod(item, "j"))
                                    + "|" + id + "|" + vals[0] + "|" + vals[1];
                            alive.add(stateKey);
                            Integer before = baselines.put(stateKey, now);
                            if (Logic.shouldTrigger(vals, before, now)) {
                                final Object candidate = item;
                                Handler worker = (Handler) XposedHelpers.getObjectField(engine, "e");
                                worker.post(() -> {
                                    Sample latest = currentSample();
                                    if (latest == null || !latest.epoch.equals(sample.epoch)
                                            || !Logic.satisfied(vals, latest.temperature)
                                            || !items.containsValue(candidate)) return;
                                    try {
                                        dispatchChange.set(Boolean.TRUE);
                                        XposedHelpers.callMethod(engine, "Q", candidate, XposedHelpers.callMethod(candidate, "j"));
                                        log("Dispatched " + Logic.summary(vals) + " at " + Logic.display(latest.temperature)
                                                + (before == null ? " (first valid sample)" : " (crossing)"));
                                    } catch (Throwable t) { log("Dispatch failed: " + t.getClass().getSimpleName()); }
                                    finally { dispatchChange.remove(); }
                                });
                            }
                        }
                        baselines.keySet().retainAll(alive);
                    }
                } catch (Throwable t) { log("Temperature evaluation unavailable: " + t.getClass().getSimpleName()); }
            }
        }
        Evaluator evaluator = new Evaluator();
        Runnable refreshRules = () -> evaluator.evaluate(true);
        Class<?> task = XposedHelpers.findClass("com.miui.autotask.taskitem.TaskItem", loader);
        XposedHelpers.findAndHookMethod(manager, "u", task, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (battery.isInstance(p.args[0]) && markedItem(p.args[0])) events.post(refreshRules);
            }
        });
        XposedHelpers.findAndHookMethod(manager, "q1", String.class, java.util.List.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) { events.post(refreshRules); }
        });
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Sample.KEY), false,
                new ContentObserver(events) {
                    public void onChange(boolean selfChange) { evaluator.evaluate(false); }
                });
        events.post(refreshRules);
    }
}
