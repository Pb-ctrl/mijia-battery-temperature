package cn.local.mijiabattery;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.ScrollView;

public class StatusActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView text;
    private final Runnable refresh = new Runnable() {
        public void run() { update(); handler.postDelayed(this, 5000); }
    };
    public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this); text.setTextSize(17); text.setPadding(32, 48, 32, 48);
        ScrollView scroll = new ScrollView(this); scroll.addView(text); setContentView(scroll);
    }
    protected void onResume() { super.onResume(); handler.post(refresh); }
    protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private String version(String pkg) {
        try { return getPackageManager().getPackageInfo(pkg, 0).versionName; }
        catch (Exception e) { return "未读取到"; }
    }
    private void update() {
        long beat = Settings.Global.getLong(getContentResolver(), "mijia_bt_heartbeat", 0);
        long age = System.currentTimeMillis() / 1000 - beat;
        int temp = Settings.Global.getInt(getContentResolver(), "mijia_bt_temp", Integer.MIN_VALUE);
        boolean verified = Settings.Global.getInt(getContentResolver(), "mijia_bt_source_verified", 0) == 1;
        boolean online = verified && beat > 0 && age >= 0 && age < 45;
        String reading = !online || temp == Integer.MIN_VALUE ? "真实温度来源不可用／未核查" : Logic.display(temp);
        String source = Settings.Global.getString(getContentResolver(), "mijia_bt_source");
        text.setText("米家电池温度扩展 · 严格来源版 0.2.1-preview\n\n"
            + "KSU 采样：" + (online ? "在线，节点来源检查通过" : "停止，不执行温度规则")
            + "\n电池温度：" + reading
            + "\n采样来源：" + (online && source != null ? source : "不可用")
            + "\n检查结果取决于当前采样分支。无已识别伪装模块时核对 battery thermal 节点和标准驱动接口；兼容分支仅检查标准驱动接口。节点未被覆盖不等于独立硬件测温证明。"
            + "\n米家：" + version("com.xiaomi.smarthome")
            + "\n手机管家：" + version("com.miui.securitycenter")
            + "\n\n1. 在 KernelSU 安装配套 ZIP。\n2. 在 LSPosed 启用本扩展，作用域勾选“米家”和“手机管家”。\n3. 重启后进入米家 → 本机 → 当个人设备发生变化时，选择“电池温度变化”。"
            + "\n\n只在充电时提供有效温度，支持任意温度变化，以及高于／低于指定温度时触发。首次有效读数满足条件会触发一次（包括任意变化）；随后高低阈值只在跨过阈值时触发。服务恢复后也会重新建立首次读数。"
            + "\n\n适配：米家 11.1.845（11011845）；手机管家 13.8.1-261023.0.1（40001381）。升级后扩展默认停止注入。"
            + "\n\n限制：温度条件借用现有手机电量条件的传输通道，同一条规则不能与“电量使用”并用。云端保存与实际联动尚需实机验证。"
            + "\n\n停止采样：KernelSU 停用配套模块，约45秒内停止识别；列表入口需重新打开页面刷新。完全移除前，先删除相应温度规则，再在 LSPosed 关闭本扩展。"
            + "\n\n本页面只能确认采样服务状态，不能证明 LSPosed 已成功注入，也不能证明米家场景已执行。请以 LSPosed 日志和米家自动化执行记录为准。");
    }
}
