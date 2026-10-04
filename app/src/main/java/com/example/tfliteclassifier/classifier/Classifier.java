package com.example.tfliteclassifier.classifier;

import android.graphics.Bitmap;

import java.util.List;

/**
 * 视觉识别器统一接口，屏蔽目标检测与回退分类的底层实现差异。
 */
public interface Classifier {

    /**
     * 对一张图片做视觉识别，返回按置信度降序排列的结果列表。
     */
    List<Recognition> detect(Bitmap bitmap);

    /**
     * 释放模型占用的资源。
     */
    void close();
}
