package com.example.tfliteclassifier.classifier;

import android.graphics.RectF;

import java.util.Locale;

/**
 * 一次检测结果：英文名 + 中文名 + 置信度 + 检测框。
 * 检测框使用相对于输入图像的像素坐标，可能为 null（无位置信息时）。
 */
public final class Recognition {

    private final String title;
    private final String titleZh;
    private final float confidence;
    private final RectF location;

    public Recognition(String title, String titleZh, float confidence, RectF location) {
        this.title = title;
        this.titleZh = titleZh;
        this.confidence = confidence;
        this.location = location == null ? null : new RectF(location);
    }

    public String getTitle() {
        return title;
    }

    public String getTitleZh() {
        return titleZh;
    }

    public float getConfidence() {
        return confidence;
    }

    public RectF getLocation() {
        return location == null ? null : new RectF(location);
    }

    /** 是否来自目标检测器；回退分类结果没有检测框。 */
    public boolean hasLocation() {
        return location != null;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "%s (%s, %.1f%%)",
                titleZh,
                title,
                confidence * 100);
    }
}
