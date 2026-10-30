package io.github.drivedroidreborn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Original vector-like icons; no artwork is extracted from DriveDroid. */
public final class IconView extends View {
    private final String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final int color;

    public IconView(Context context) {
        this(context, "usb", 0xff757575);
    }

    public IconView(Context context, String kind, int color) {
        super(context);
        this.kind = kind;
        this.color = color;
        arrow.moveTo(12, 7);
        arrow.lineTo(16, 1);
        arrow.lineTo(20, 7);
        arrow.close();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 32, size / 32);
        paint.setColor(color);
        paint.setStrokeWidth(2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case "plus" -> {
                canvas.drawLine(8, 16, 24, 16, paint);
                canvas.drawLine(16, 8, 16, 24, paint);
            }
            case "refresh" -> {
                canvas.drawArc(6, 6, 26, 26, 35, 290, false, paint);
                canvas.drawLine(25, 5, 25, 12, paint);
                canvas.drawLine(18, 12, 25, 12, paint);
            }
            case "settings" -> {
                canvas.drawCircle(16, 16, 8, paint);
                canvas.drawCircle(16, 16, 3, paint);
                for (int angle = 0; angle < 360; angle += 45) {
                    canvas.save();
                    canvas.rotate(angle, 16, 16);
                    canvas.drawLine(16, 4, 16, 8, paint);
                    canvas.restore();
                }
            }
            case "help" -> {
                canvas.drawCircle(16, 16, 11, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(22);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("?", 16, 24, paint);
            }
            case "back" -> {
                canvas.drawLine(6, 16, 26, 16, paint);
                canvas.drawLine(6, 16, 15, 7, paint);
                canvas.drawLine(6, 16, 15, 25, paint);
            }
            case "arrow" -> {
                canvas.drawLine(11, 7, 20, 16, paint);
                canvas.drawLine(20, 16, 11, 25, paint);
            }
            case "disc" -> {
                canvas.drawCircle(16, 16, 12, paint);
                canvas.drawCircle(16, 16, 3, paint);
                canvas.drawArc(7, 7, 25, 25, 190, 65, false, paint);
            }
            case "download" -> {
                canvas.drawLine(16, 4, 16, 21, paint);
                canvas.drawLine(10, 15, 16, 21, paint);
                canvas.drawLine(22, 15, 16, 21, paint);
                canvas.drawLine(7, 26, 25, 26, paint);
            }
            default -> {
                canvas.drawLine(16, 24, 16, 6, paint);
                canvas.drawLine(16, 21, 8, 15, paint);
                canvas.drawLine(8, 15, 8, 10, paint);
                canvas.drawLine(16, 16, 24, 11, paint);
                canvas.drawLine(24, 11, 24, 7, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(16, 26, 3, paint);
                canvas.drawCircle(8, 8, 2, paint);
                canvas.drawRect(22, 4, 26, 8, paint);
                canvas.drawPath(arrow, paint);
            }
        }
        canvas.restore();
    }
}
