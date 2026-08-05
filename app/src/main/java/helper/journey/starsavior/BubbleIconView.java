package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewOutlineProvider;

final class BubbleIconView extends View {
    static final int TOUCH_SIZE_DP = 58;

    private static final String DEFAULT_GLYPH = "✦";
    private static final float GLYPH_SIZE_SP = 28f;
    private static final float GLYPH_MARGIN_DP = 2f;
    private static final float STROKE_WIDTH_DP = 1f;
    private static final int ACTIVE_CIRCLE = Color.rgb(112, 92, 235);
    private static final int ACTIVE_STROKE = Color.rgb(205, 197, 255);
    private static final int WAITING_CIRCLE = Color.rgb(78, 74, 98);
    private static final int WAITING_STROKE = Color.rgb(174, 168, 198);

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final RectF glyphBounds = new RectF();
    private final int maximumDiameter;
    private final int minimumDiameter;
    private int circleDiameter;
    private boolean active = true;

    BubbleIconView(Context context) {
        super(context);
        maximumDiameter = Ui.dp(context, TOUCH_SIZE_DP);

        circlePaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Ui.dp(context, STROKE_WIDTH_DP));

        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(Color.WHITE);
        glyphPaint.setTypeface(Typeface.DEFAULT);
        glyphPaint.setTextSize(GLYPH_SIZE_SP * getResources().getDisplayMetrics().scaledDensity);

        updateGlyphPath(DEFAULT_GLYPH);
        minimumDiameter = calculateMinimumDiameter(context);
        circleDiameter = maximumDiameter;
        updateColors();

        setElevation(Ui.dp(context, 10));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                    outline.setEmpty();
                    return;
                }
                int left = (view.getWidth() - circleDiameter) / 2;
                int top = (view.getHeight() - circleDiameter) / 2;
                outline.setOval(left, top, left + circleDiameter, top + circleDiameter);
            }
        });
    }

    void setCircleProgress(int progress) {
        int diameter = BubbleAppearance.diameterForProgress(
                minimumDiameter, maximumDiameter, progress);
        if (circleDiameter == diameter) return;
        circleDiameter = diameter;
        invalidateOutline();
        invalidate();
    }

    void setGlyph(String value) {
        String next = value == null || value.isEmpty() ? DEFAULT_GLYPH : value;
        updateGlyphPath(next);
        invalidate();
    }

    void setCaptureActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        updateColors();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float outerRadius = circleDiameter / 2f;
        float strokeRadius = Math.max(0f, outerRadius - strokePaint.getStrokeWidth() / 2f);

        // Drawing in one View guarantees that the background can never cover
        // the glyph because child elevation no longer controls their Z order.
        canvas.drawCircle(centerX, centerY, outerRadius, circlePaint);
        canvas.drawCircle(centerX, centerY, strokeRadius, strokePaint);

        canvas.save();
        canvas.translate(centerX - glyphBounds.centerX(), centerY - glyphBounds.centerY());
        canvas.drawPath(glyphPath, glyphPaint);
        canvas.restore();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateGlyphPath(String value) {
        glyphPath.reset();
        glyphPaint.getTextPath(value, 0, value.length(), 0f, 0f, glyphPath);
        glyphPath.computeBounds(glyphBounds, true);
    }

    private int calculateMinimumDiameter(Context context) {
        float maxRadius = 0f;
        float centerX = glyphBounds.centerX();
        float centerY = glyphBounds.centerY();
        float[] points = glyphPath.approximate(0.25f);
        for (int index = 0; index + 2 < points.length; index += 3) {
            float dx = points[index + 1] - centerX;
            float dy = points[index + 2] - centerY;
            maxRadius = Math.max(maxRadius, (float) Math.hypot(dx, dy));
        }

        float safeRadius = maxRadius
                + Ui.dp(context, GLYPH_MARGIN_DP)
                + strokePaint.getStrokeWidth() / 2f;
        return Math.min(maximumDiameter, (int) Math.ceil(safeRadius * 2f));
    }

    private void updateColors() {
        circlePaint.setColor(active ? ACTIVE_CIRCLE : WAITING_CIRCLE);
        strokePaint.setColor(active ? ACTIVE_STROKE : WAITING_STROKE);
    }
}
