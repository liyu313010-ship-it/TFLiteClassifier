package com.example.tfliteclassifier.classifier;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Classifications;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 混合视觉识别器：优先使用 EfficientDet-Lite2 检测 COCO 日常物体；
 * 未检出时使用 EfficientNet-Lite2 对完整画面做 ImageNet 1000 类回退分类。
 */
public class ImageClassifier implements Classifier {

    private static final String DETECTOR_MODEL_FILE = "efficientdet_lite2.tflite";
    private static final String FALLBACK_MODEL_FILE = "efficientnet_lite2.tflite";
    private static final String IMAGENET_ZH_LABEL_FILE = "labels_zh.txt";
    private static final float DETECTOR_SCORE_THRESHOLD = 0.35f;
    private static final float FALLBACK_SCORE_THRESHOLD = 0.12f;
    private static final int MAX_RESULTS = 3;

    private final ObjectDetector detector;
    private final com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier fallbackClassifier;
    private final Map<String, String> zhLabels;
    private final List<String> imageNetZhLabels;

    public ImageClassifier(Context context) {
        zhLabels = buildZhLabels();
        imageNetZhLabels = loadImageNetZhLabels(context);

        ObjectDetector.ObjectDetectorOptions options =
                ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setModelAssetPath(DETECTOR_MODEL_FILE)
                                .build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setMaxResults(MAX_RESULTS)
                        .setScoreThreshold(DETECTOR_SCORE_THRESHOLD)
                        .build();
        ObjectDetector createdDetector = ObjectDetector.createFromOptions(context, options);

        try {
            com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier.ImageClassifierOptions
                    classifierOptions = com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
                    .ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                            .setModelAssetPath(FALLBACK_MODEL_FILE)
                            .build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(MAX_RESULTS)
                    // 过滤接近随机猜测的结果，避免“为了有结果而误报”。
                    .setScoreThreshold(FALLBACK_SCORE_THRESHOLD)
                    .build();
            fallbackClassifier =
                    com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
                            .createFromOptions(context, classifierOptions);
            detector = createdDetector;
        } catch (RuntimeException exception) {
            createdDetector.close();
            throw exception;
        }
    }

    @Override
    public List<Recognition> detect(Bitmap bitmap) {
        try (MPImage mpImage = new BitmapImageBuilder(bitmap).build()) {
            ObjectDetectorResult result = detector.detect(mpImage);

            List<Recognition> recognitions = new ArrayList<>();
            for (Detection detection : result.detections()) {
                RectF box = detection.boundingBox();
                if (detection.categories().isEmpty()) {
                    continue;
                }
                Category category = detection.categories().get(0);
                String labelEn = category.categoryName();
                String labelZh = zhLabels.getOrDefault(
                        labelEn.toLowerCase(Locale.US),
                        labelEn);
                recognitions.add(new Recognition(labelEn, labelZh, category.score(), box));
            }
            if (!recognitions.isEmpty()) {
                return recognitions;
            }

            return classifyFallback(mpImage);
        }
    }

    @Override
    public void close() {
        detector.close();
        fallbackClassifier.close();
    }

    private List<Recognition> classifyFallback(MPImage image) {
        List<Classifications> heads = fallbackClassifier.classify(image)
                .classificationResult()
                .classifications();
        if (heads.isEmpty()) {
            return new ArrayList<>();
        }

        List<Recognition> recognitions = new ArrayList<>();
        for (Category category : heads.get(0).categories()) {
            int zhIndex = category.index() + 1; // 中文标签文件的第 0 行是 background。
            String labelEn = category.categoryName();
            String labelZh = zhIndex >= 1 && zhIndex < imageNetZhLabels.size()
                    ? imageNetZhLabels.get(zhIndex)
                    : labelEn;
            recognitions.add(new Recognition(labelEn, labelZh, category.score(), null));
        }
        return recognitions;
    }

    private static List<String> loadImageNetZhLabels(Context context) {
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(IMAGENET_ZH_LABEL_FILE),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line.trim());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 ImageNet 中文标签", exception);
        }
        if (labels.size() != 1001) {
            throw new IllegalStateException(
                    "ImageNet 中文标签应为 1001 行，实际为 " + labels.size());
        }
        return labels;
    }

    /**
     * COCO 80 类英文名到中文名的映射。
     */
    private static Map<String, String> buildZhLabels() {
        Map<String, String> map = new HashMap<>();
        map.put("person", "人");
        map.put("bicycle", "自行车");
        map.put("car", "汽车");
        map.put("motorcycle", "摩托车");
        map.put("airplane", "飞机");
        map.put("bus", "公交车");
        map.put("train", "火车");
        map.put("truck", "卡车");
        map.put("boat", "船");
        map.put("traffic light", "交通灯");
        map.put("fire hydrant", "消防栓");
        map.put("stop sign", "停车标志");
        map.put("parking meter", "停车计时器");
        map.put("bench", "长椅");
        map.put("bird", "鸟");
        map.put("cat", "猫");
        map.put("dog", "狗");
        map.put("horse", "马");
        map.put("sheep", "羊");
        map.put("cow", "牛");
        map.put("elephant", "大象");
        map.put("bear", "熊");
        map.put("zebra", "斑马");
        map.put("giraffe", "长颈鹿");
        map.put("backpack", "背包");
        map.put("umbrella", "雨伞");
        map.put("handbag", "手提包");
        map.put("tie", "领带");
        map.put("suitcase", "行李箱");
        map.put("frisbee", "飞盘");
        map.put("skis", "滑雪板");
        map.put("snowboard", "单板滑雪");
        map.put("sports ball", "运动球");
        map.put("kite", "风筝");
        map.put("baseball bat", "棒球棒");
        map.put("baseball glove", "棒球手套");
        map.put("skateboard", "滑板");
        map.put("surfboard", "冲浪板");
        map.put("tennis racket", "网球拍");
        map.put("bottle", "瓶子");
        map.put("wine glass", "酒杯");
        map.put("cup", "杯子");
        map.put("fork", "叉子");
        map.put("knife", "刀");
        map.put("spoon", "勺子");
        map.put("bowl", "碗");
        map.put("banana", "香蕉");
        map.put("apple", "苹果");
        map.put("sandwich", "三明治");
        map.put("orange", "橙子");
        map.put("broccoli", "西兰花");
        map.put("carrot", "胡萝卜");
        map.put("hot dog", "热狗");
        map.put("pizza", "披萨");
        map.put("donut", "甜甜圈");
        map.put("cake", "蛋糕");
        map.put("chair", "椅子");
        map.put("couch", "沙发");
        map.put("potted plant", "盆栽");
        map.put("bed", "床");
        map.put("dining table", "餐桌");
        map.put("toilet", "马桶");
        map.put("tv", "电视");
        map.put("laptop", "笔记本电脑");
        map.put("mouse", "鼠标");
        map.put("remote", "遥控器");
        map.put("keyboard", "键盘");
        map.put("cell phone", "手机");
        map.put("microwave", "微波炉");
        map.put("oven", "烤箱");
        map.put("toaster", "烤面包机");
        map.put("sink", "水槽");
        map.put("refrigerator", "冰箱");
        map.put("book", "书");
        map.put("clock", "时钟");
        map.put("vase", "花瓶");
        map.put("scissors", "剪刀");
        map.put("teddy bear", "泰迪熊");
        map.put("hair drier", "吹风机");
        map.put("toothbrush", "牙刷");
        return map;
    }
}
