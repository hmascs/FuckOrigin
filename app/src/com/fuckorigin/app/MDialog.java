package com.fuckorigin.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * miuix 风格对话框：24dp 圆角白卡、20sp 标题、14sp 正文、底部按钮右对齐。
 * 替代框架 AlertDialog（后者是 Material2 样式，与本项目界面不搭）。
 */
public final class MDialog {

    public interface OnClick { void run(Dialog d); }

    private final Activity a;
    private String title, message;
    private String positive = "确定", negative, neutral;
    private OnClick onPositive, onNegative, onNeutral;
    private View custom;
    private int maxHeightDp = 0;
    private Dialog dlg;

    public MDialog(Activity a) { this.a = a; }

    public MDialog title(String t) { title = t; return this; }
    public MDialog message(String m) { message = m; return this; }
    public MDialog custom(View v, int maxH) { custom = v; maxHeightDp = maxH; return this; }
    public MDialog positive(String t, OnClick c) { positive = t; onPositive = c; return this; }
    public MDialog negative(String t, OnClick c) { negative = t; onNegative = c; return this; }
    public MDialog neutral(String t, OnClick c) { neutral = t; onNeutral = c; return this; }

    public Dialog show() {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.card));
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        card.setPadding(dp(22), dp(20), dp(22), dp(14));

        if (title != null) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(20);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setTextColor(a.getColor(R.color.text));
            card.addView(t);
        }
        if (message != null) {
            TextView m = new TextView(a);
            m.setText(message);
            m.setTextSize(14);
            m.setTextColor(a.getColor(R.color.dim));
            m.setLineSpacing(dp(4), 1f);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = dp(title == null ? 0 : 10);
            card.addView(m, mp);
        }
        if (custom != null) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = dp(12);
            if (maxHeightDp > 0) cp.height = dp(maxHeightDp);
            card.addView(custom, cp);
        }

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(14);
        if (neutral != null) buttons.addView(btn(neutral, R.color.accent, new OnClick() {
            public void run(Dialog dd) { if (onNeutral != null) onNeutral.run(dd); else dd.dismiss(); }
        }));
        if (negative != null) buttons.addView(btn(negative, R.color.dim, new OnClick() {
            public void run(Dialog dd) { if (onNegative != null) onNegative.run(dd); else dd.dismiss(); }
        }));
        buttons.addView(btn(positive, R.color.accent, new OnClick() {
            public void run(Dialog dd) { if (onPositive != null) onPositive.run(dd); else dd.dismiss(); }
        }));
        card.addView(buttons, bp);

        ScrollView wrap = new ScrollView(a);
        wrap.addView(card);
        dlg = d;
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f);
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
        }
        d.show();
        return d;
    }

    private TextView btn(String text, int colorRes, final OnClick c) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(a.getColor(colorRes));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setGravity(Gravity.CENTER);
        GradientDrawable rip = new GradientDrawable();
        rip.setColor(Color.TRANSPARENT);
        rip.setCornerRadius(dp(12));
        t.setBackground(rip);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { c.run(dlg); if (dlg != null && dlg.isShowing()) dlg.dismiss(); }
        });
        return t;
    }

    private int dp(int v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }
}
