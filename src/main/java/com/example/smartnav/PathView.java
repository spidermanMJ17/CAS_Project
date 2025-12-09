package com.example.smartnav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

public class PathView extends View {

    private Paint drPaint, slamPaint, gridPaint, markerPaint;
    private Path drPath, slamPath;

    // Touch Detectors
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    // View State
    private float scaleFactor = 50.0f; // Default Zoom: 50 pixels = 1 meter
    private float translateX = 0f;
    private float translateY = 0f;

    public PathView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Setup Paints
        drPaint = new Paint();
        drPaint.setColor(Color.BLUE);
        drPaint.setStyle(Paint.Style.STROKE);
        drPaint.setStrokeWidth(5);
        drPaint.setStrokeCap(Paint.Cap.ROUND);

        slamPaint = new Paint();
        slamPaint.setColor(Color.RED);
        slamPaint.setStyle(Paint.Style.STROKE);
        slamPaint.setStrokeWidth(5);
        slamPaint.setStrokeCap(Paint.Cap.ROUND);

        gridPaint = new Paint();
        gridPaint.setColor(0xFFDDDDDD); // Light Grey
        gridPaint.setStrokeWidth(2);

        markerPaint = new Paint();
        markerPaint.setColor(Color.BLACK);
        markerPaint.setStyle(Paint.Style.FILL);

        drPath = new Path();
        drPath.moveTo(0,0);
        slamPath = new Path();
        slamPath.moveTo(0,0);

        // Setup Input Listeners
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new ScrollListener());
    }

    public void updateDrPosition(float x, float y) {
        drPath.lineTo(x, y);
        invalidate();
    }

    public void updateSlamPosition(float x, float y) {
        slamPath.lineTo(x, y);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();

        // 1. Move Origin to Center of Screen
        canvas.translate(getWidth() / 2f, getHeight() / 2f);

        // 2. Apply Panning
        canvas.translate(translateX, translateY);

        // 3. Apply Zoom
        canvas.scale(scaleFactor, -scaleFactor); // Flip Y

        // 4. Draw Grid (Fixed 20m x 20m area)
        float strokeWidth = 2.0f / scaleFactor;
        gridPaint.setStrokeWidth(strokeWidth);
        for (int i = -20; i <= 20; i+=2) {
            canvas.drawLine(i, -20, i, 20, gridPaint);
            canvas.drawLine(-20, i, 20, i, gridPaint);
        }

        // 5. Draw Paths
        float pathWidth = 5.0f / scaleFactor;
        drPaint.setStrokeWidth(pathWidth);
        slamPaint.setStrokeWidth(pathWidth);

        canvas.drawPath(drPath, drPaint);
        canvas.drawPath(slamPath, slamPaint);

        // 6. Draw Center Marker
        canvas.drawCircle(0, 0, 0.1f, markerPaint);

        canvas.restore();
    }

    public void resetPath() {
        drPath.reset(); drPath.moveTo(0,0);
        slamPath.reset(); slamPath.moveTo(0,0);
        translateX = 0; translateY = 0;
        invalidate();
    }

    // --- IMPROVED TOUCH HANDLING ---
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Always process both detectors
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            // Clamp zoom (Min: 5px/m, Max: 500px/m)
            scaleFactor = Math.max(5.0f, Math.min(scaleFactor, 500.0f));
            invalidate();
            return true;
        }
    }

    private class ScrollListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Move the camera opposite to the drag direction
            translateX -= distanceX;
            translateY -= distanceY;
            invalidate();
            return true;
        }
    }
}