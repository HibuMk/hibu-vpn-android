/*
 * 黑龙江工商学院 VPN 客户端 —— 崩溃详情页
 * 用系统主题 + 纯代码搭界面，避免自身再出问题
 */
package de.blinkt.openvpn.activities;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String trace = getIntent() != null ? getIntent().getStringExtra("crash_trace") : null;
        if (trace == null || trace.isEmpty()) trace = "(无崩溃信息)";

        float d = getResources().getDisplayMetrics().density;
        int p = (int) (14 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("客户端崩溃信息（请截图发管理员）");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#dc2626"));
        title.setPadding(0, 0, 0, p);
        root.addView(title);

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#0f172a"));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final String t = trace;
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("复制全部");
        copy.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("crash", t));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
        btns.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> finish());
        btns.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btns);
        setContentView(root);
    }
}
