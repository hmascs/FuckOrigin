package com.fuckorigin.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * miuix 风格开关：胶囊轨道 + 圆形滑块 + 拖尾动画。
 * 尺寸对齐 HyperCeiler 截图（轨道 44x26dp，滑块 22dp）。
 */
public class MiuixSwitch extends View {

    private boolean checked;
    private float pos;              // 0..1
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int onColor = 0xFF3482FF, offColor = 0xFFE5E5EA;
    private ValueAnimator anim;
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener { void onCheckedChanged(MiuixSwitch v, boolean checked); }

    public MiuixSwitch(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        int w = (int) (44 * d), h = (int) (26 * d);
        setLayoutParams(new android.view.ViewGroup.LayoutParams(w, h));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(d);
        border.setColor(0x14000000);
        setClickable(true);
        setFocusable(true);
        setContentDescription("开关");
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) { listener = l; }
    public boolean isChecked() { return checked; }

    public void setChecked(boolean v) { setChecked(v, false); }

    public void setChecked(boolean v, boolean animate) {
        if (checked == v && pos == (v ? 1f : 0f)) return;
        checked = v;
        float target = v ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!animate) { pos = target; invalidate(); return; }
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(180);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override public boolean performClick() {
        boolean nv = !checked;
        setChecked(nv, true);
        if (listener != null) listener.onCheckedChanged(this, nv);
        return super.performClick();
    }

    @Override protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight(), r = h / 2f;
        int base = offColor, target = onColor;
        int trackColor = blend(base, target, pos);
        track.setColor(trackColor);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);

        float pad = 2 * d;
        float size = h - pad * 2;
        float cx = pad + size / 2f + (w - size - pad * 2) * pos;
        thumb.setColor(0xFFFFFFFF);
        thumb.setShadowLayer(2 * d, 0, d * 0.5f, 0x33000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        canvas.drawCircle(cx, h / 2f, size / 2f, thumb);
    }

    private int blend(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t), (int) (ab + (bb - ab) * t));
    }
}
