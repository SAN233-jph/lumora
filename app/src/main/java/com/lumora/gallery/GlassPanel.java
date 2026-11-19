package com.lumora.gallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Liquid-glass panel: draws a live copy of whatever is behind it (the "source" view),
 * blurs it, bends it near the rim (refraction), boosts colour, then adds glossy edges.
 */
public class GlassPanel extends FrameLayout {

    private static final String AGSL =
            "uniform shader content;\n"
          + "uniform float2 size;\n"
          + "uniform float radius;\n"
          + "float sd(float2 p) {\n"
          + "  float2 c = p - size * 0.5;\n"
          + "  float2 q = abs(c) - (size * 0.5 - float2(radius));\n"
          + "  return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
          + "}\n"
          + "half4 main(float2 p) {\n"
          + "  float d = sd(p);\n"
          + "  float e = clamp(-d / 22.0, 0.0, 1.0);\n"
          + "  float k = pow(1.0 - e, 2.5) * 16.0;\n"
          + "  float2 g = float2(sd(p + float2(1.0, 0.0)) - sd(p - float2(1.0, 0.0)),\n"
          + "                    sd(p + float2(0.0, 1.0)) - sd(p - float2(0.0, 1.0)));\n"
          + "  float2 uv = clamp(p - normalize(g + float2(0.0001)) * k, float2(1.0), size - float2(1.0));\n"
          + "  half4 c = content.eval(uv);\n"
          + "  half l = dot(c.rgb, half3(0.299, 0.587, 0.114));\n"
          + "  c.rgb = half3(l) + (c.rgb - half3(l)) * 1.35 + half3(0.07);\n"
          + "  return c;\n"
          + "}\n";

    private final View source;
    private final float radius;
    private final Backdrop backdrop;
    private final RuntimeShader shader = new RuntimeShader(AGSL);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GlassPanel(Context ctx, View source, float radiusPx) {
        super(ctx);
        this.source = source;
        this.radius = radiusPx;
        setWillNotDraw(false);
        backdrop = new Backdrop(ctx);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
                        Math.min(radius, Math.min(v.getWidth(), v.getHeight()) / 2f));
            }
        });
        setClipToOutline(true);
        setElevation(14f * ctx.getResources().getDisplayMetrics().density);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.5f);
        gloss.setStyle(Paint.Style.FILL);
    }

    /** Call whenever the content behind the panel changes (scroll, animation, new image). */
    public void refresh() {
        backdrop.invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0) return;
        shader.setFloatUniform("size", (float) w, (float) h);
        shader.setFloatUniform("radius", Math.min(radius, Math.min(w, h) / 2f));
        backdrop.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content"),
                RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP)));
        stroke.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xDDFFFFFF, 0x14FFFFFF, 0x14FFFFFF, 0xAAFFFFFF},
                new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.6f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        float w = getWidth(), h = getHeight();
        float r = Math.min(radius, Math.min(w, h) / 2f);
        rect.set(1.5f, 1.5f, w - 1.5f, h - 1.5f);
        c.drawRoundRect(rect, r, r, gloss);
        c.drawRoundRect(rect, r, r, stroke);
    }

    private class Backdrop extends View {
        Backdrop(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF101216);
            int[] a = new int[2];
            int[] b = new int[2];
            source.getLocationInWindow(a);
            getLocationInWindow(b);
            canvas.save();
            canvas.translate(a[0] - b[0], a[1] - b[1]);
            source.draw(canvas);
            canvas.restore();
            canvas.drawColor(0x22FFFFFF);
        }
    }
}
