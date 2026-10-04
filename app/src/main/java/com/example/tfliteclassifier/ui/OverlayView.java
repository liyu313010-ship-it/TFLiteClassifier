package com.example.tfliteclassifier.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.example.tfliteclassifier.R;
import com.example.tfliteclassifier.classifier.Recognition;

import java.util.ArrayList;
import java.util.List;

/**
 * 叠加在预览图之上的透明层，用品牌色绘制检测框与标签。
 * 检测框坐标相对于输入图像，这里按 centerCrop/fillCenter 方式映射到 View 坐标。
 */
public class OverlayView extends View {

    private List<Recognition> detections = new ArrayList<>();
    private float imageWidth = 1f;
    private float imageHeight = 1f;
    private boolean mirrored;

    private final Paint boxPaint;
    private final Paint labelBgPaint;
    private final Paint labelTextPaint;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(3));
        boxPaint.setColor(ContextCompat.getColor(context, R.color.brand_primary));
        boxPaint.setAntiAlias(true);

        labelBgPaint = new Paint();
        labelBgPaint.setStyle(Paint.Style.FILL);
        labelBgPaint.setColor(ContextCompat.getColor(context, R.color.brand_primary_dark));
        labelBgPaint.setAntiAlias(true);

        labelTextPaint = new Paint();
        labelTextPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        labelTextPaint.setTextSize(sp(13));
        labelTextPaint.setAntiAlias(true);
        labelTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setImageSize(float width, float height) {
        this.imageWidth = Math.max(1f, width);
        this.imageHeight = Math.max(1f, height);
    }

    /** 前置摄像头预览为镜像画面，检测框也需要同步水平镜像。 */
    public void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        invalidate();
    }

    public void setDetections(List<Recognition> newDetections) {
        this.detections = newDetections == null
                ? new ArrayList<>()
                : new ArrayList<>(newDetections);
        invalidate();
    }

    public void clear() {
        this.detections.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (detections.isEmpty()) {
            return;
        }

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        // PreviewView 与相册 ImageView 都使用 centerCrop/fillCenter，因此取较大缩放比。
        float scale = Math.max(viewWidth / imageWidth, viewHeight / imageHeight);
        float offsetX = (viewWidth - imageWidth * scale) / 2f;
        float offsetY = (viewHeight - imageHeight * scale) / 2f;

        for (Recognition recognition : detections) {
            RectF box = recognition.getLocation();
            if (box == null) {
                continue;
            }

            float left = box.left * scale + offsetX;
            float top = box.top * scale + offsetY;
            float right = box.right * scale + offsetX;
            float bottom = box.bottom * scale + offsetY;
            if (mirrored) {
                float mirroredLeft = viewWidth - right;
                right = viewWidth - left;
                left = mirroredLeft;
            }

            // 品牌色检测框
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // 标签：中文名 + 置信度
            String label = recognition.getTitleZh()
                    + " "
                    + Math.round(recognition.getConfidence() * 100)
                    + "%";
            float textWidth = labelTextPaint.measureText(label);
            float textHeight = labelTextPaint.getTextSize() + dp(6);
            float labelTop = top - textHeight;
            if (labelTop < 0) {
                labelTop = top;
            }
            float labelLeft = Math.max(0f, Math.min(left, viewWidth - dp(10)));
            float labelRight = Math.min(viewWidth, labelLeft + textWidth + dp(10));
            canvas.drawRect(
                    labelLeft,
                    labelTop,
                    labelRight,
                    labelTop + textHeight,
                    labelBgPaint);
            canvas.drawText(
                    label,
                    labelLeft + dp(5),
                    labelTop + textHeight - dp(5),
                    labelTextPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics());
    }
}
