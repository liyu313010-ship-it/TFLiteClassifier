package com.example.tfliteclassifier;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tfliteclassifier.classifier.Classifier;
import com.example.tfliteclassifier.classifier.ImageClassifier;
import com.example.tfliteclassifier.classifier.Recognition;
import com.example.tfliteclassifier.cloud.CloudRecognizer;
import com.example.tfliteclassifier.env.Logger;
import com.example.tfliteclassifier.speech.SpeechGate;
import com.example.tfliteclassifier.ui.OverlayView;
import com.example.tfliteclassifier.ui.ResultsAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 主界面：在相机实时识别和相册单图识别之间进行显式切换。
 * 所有模型操作都在同一个后台线程中串行执行。
 */
public class MainActivity extends AppCompatActivity {

    private enum Mode {
        CAMERA,
        GALLERY
    }

    private static final long ANALYSIS_INTERVAL_MS = 250L;
    private static final long CLOUD_ANALYSIS_INTERVAL_MS = 2000L;
    private static final int MAX_PICKED_IMAGE_DIMENSION = 2048;
    private static final Size ANALYSIS_RESOLUTION = new Size(640, 480);
    private static final String STATE_MODE = "mode";
    private static final String STATE_IMAGE_URI = "image_uri";
    private static final String STATE_LENS_FACING = "lens_facing";
    private static final String STATE_ZOOM_RATIO = "zoom_ratio";
    private static final String PREFERENCES_NAME = "vision_ai_preferences";
    private static final String PREFERENCE_VOICE_ENABLED = "voice_enabled";
    private static final String PREFERENCE_CLOUD_ENABLED = "cloud_enabled";

    private PreviewView previewView;
    private ImageView selectedImageView;
    private TextView statusText;
    private TextView mediaHint;
    private TextView resultsEmptyText;
    private View resultsEmptyState;
    private MaterialButton liveCameraButton;
    private MaterialButton pickImageButton;
    private MaterialButton flipCameraButton;
    private MaterialButton zoomOutButton;
    private MaterialButton zoomInButton;
    private TextView zoomRatioText;
    private View cameraZoomControls;
    private ResultsAdapter adapter;
    private OverlayView overlayView;
    private RecyclerView resultsList;
    private TextView cloudResultText;
    private ScrollView cloudResultScroll;
    private CloudRecognizer cloudRecognizer;

    private MaterialSwitch voiceSwitch;
    private MaterialSwitch cloudSwitch;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean zhTtsReady;
    private boolean ttsPromptShown;
    private boolean ttsFailureNotified;
    private Recognition latestTopRecognition;
    private String latestCloudDescription;
    private Mode latestTopMode;
    private final SpeechGate speechGate = new SpeechGate(3);

    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private Classifier classifier; // 只允许 inferenceExecutor 线程访问
    private volatile boolean classifierReady;
    private volatile boolean destroyed;

    private volatile Mode mode = Mode.CAMERA;
    private volatile long modeGeneration;
    private long lastAnalysisTime;

    private ProcessCameraProvider cameraProvider;
    private Camera boundCamera;
    private ImageAnalysis imageAnalysis;
    private boolean cameraBound;
    private boolean cameraPermissionDenied;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private float requestedZoomRatio = 1f;
    private Bitmap selectedBitmap;
    private Uri selectedImageUri;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    enterGalleryMode(uri);
                } else {
                    updateModeButtons();
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    cameraPermissionDenied = false;
                    startCameraIfPossible();
                } else {
                    cameraPermissionDenied = true;
                    mode = Mode.GALLERY;
                    modeGeneration++;
                    stopCamera();
                    updateModeButtons();
                    setStatus(R.string.status_camera_permission_denied);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        int initialPaddingLeft = root.getPaddingLeft();
        int initialPaddingTop = root.getPaddingTop();
        int initialPaddingRight = root.getPaddingRight();
        int initialPaddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    initialPaddingLeft + bars.left,
                    initialPaddingTop + bars.top,
                    initialPaddingRight + bars.right,
                    initialPaddingBottom + bars.bottom);
            return windowInsets;
        });

        previewView = findViewById(R.id.preview_view);
        selectedImageView = findViewById(R.id.selected_image);
        statusText = findViewById(R.id.status_text);
        mediaHint = findViewById(R.id.media_hint);
        resultsEmptyState = findViewById(R.id.results_empty_state);
        resultsEmptyText = findViewById(R.id.results_empty_text);
        liveCameraButton = findViewById(R.id.btn_live_camera);
        pickImageButton = findViewById(R.id.btn_pick_image);
        flipCameraButton = findViewById(R.id.btn_flip_camera);
        zoomOutButton = findViewById(R.id.btn_zoom_out);
        zoomInButton = findViewById(R.id.btn_zoom_in);
        zoomRatioText = findViewById(R.id.zoom_ratio_text);
        cameraZoomControls = findViewById(R.id.camera_zoom_controls);
        voiceSwitch = findViewById(R.id.switch_voice);
        cloudSwitch = findViewById(R.id.switch_cloud);
        overlayView = findViewById(R.id.overlay_view);
        cloudResultText = findViewById(R.id.cloud_result_text);
        cloudResultScroll = findViewById(R.id.cloud_result_scroll);
        resultsList = findViewById(R.id.results_list);

        adapter = new ResultsAdapter();
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setAdapter(adapter);
        // 实时结果每秒刷新数次，关闭默认动画可避免列表闪烁。
        resultsList.setItemAnimator(null);

        liveCameraButton.setOnClickListener(view -> enterCameraMode());
        flipCameraButton.setOnClickListener(view -> flipCamera());
        zoomOutButton.setOnClickListener(view -> changeZoomByFactor(0.8f));
        zoomInButton.setOnClickListener(view -> changeZoomByFactor(1.25f));
        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(
                this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        changeZoomByFactor(detector.getScaleFactor());
                        return true;
                    }
                });
        previewView.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        });
        pickImageButton.setOnClickListener(view -> pickImageLauncher.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        boolean voiceEnabled = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(PREFERENCE_VOICE_ENABLED, true);
        voiceSwitch.setChecked(voiceEnabled);
        voiceSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREFERENCE_VOICE_ENABLED, isChecked)
                    .apply();
            resetSpeechState();
            if (!isChecked) {
                if (textToSpeech != null) {
                    textToSpeech.stop();
                }
                Toast.makeText(this, R.string.voice_disabled, Toast.LENGTH_SHORT).show();
            } else if (ttsReady) {
                Toast.makeText(this, R.string.voice_enabled, Toast.LENGTH_SHORT).show();
                maybeSpeakLatest();
            } else {
                Toast.makeText(this, R.string.voice_initializing, Toast.LENGTH_SHORT).show();
                if (textToSpeech == null) {
                    initTextToSpeech();
                }
            }
        });

        boolean cloudEnabled = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(PREFERENCE_CLOUD_ENABLED, false);
        cloudSwitch.setChecked(cloudEnabled);
        cloudSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREFERENCE_CLOUD_ENABLED, isChecked)
                    .apply();
            resetResultsForModeSwitch();
            if (isChecked) {
                setStatus(R.string.status_model_loading);
                Toast.makeText(this, R.string.cloud_recognition, Toast.LENGTH_SHORT).show();
            }
            // 相册模式切换识别引擎后，立即用当前图片重新识别。
            if (mode == Mode.GALLERY && selectedImageUri != null && classifierReady) {
                enterGalleryMode(selectedImageUri);
            } else {
                renderIdleStatus();
            }
        });

        if (savedInstanceState != null
                && Mode.GALLERY.name().equals(savedInstanceState.getString(STATE_MODE))) {
            mode = Mode.GALLERY;
            String savedUri = savedInstanceState.getString(STATE_IMAGE_URI);
            if (savedUri != null) {
                selectedImageUri = Uri.parse(savedUri);
            }
        }
        if (savedInstanceState != null) {
            lensFacing = savedInstanceState.getInt(
                    STATE_LENS_FACING,
                    CameraSelector.LENS_FACING_BACK);
            requestedZoomRatio = savedInstanceState.getFloat(STATE_ZOOM_RATIO, 1f);
        }

        pickImageButton.setEnabled(false);
        updateModeButtons();
        showCreatorDialog();
    }

    /**
     * 启动时先弹出创作者信息，点击确认后才开始加载模型与启动相机。
     */
    private void showCreatorDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.creator_title)
                .setMessage(R.string.creator_name)
                .setCancelable(false)
                .setPositiveButton(R.string.creator_confirm, (dialog, which) -> startWorking())
                .show();
    }

    private void startWorking() {
        setStatus(R.string.status_model_loading);
        initTextToSpeech();
        initClassifier();

        if (mode == Mode.GALLERY) {
            // 模型加载完成后重新解码旋转前选中的图片。
        } else if (hasCamera()) {
            requestCameraPermissionIfNeeded();
        } else {
            mode = Mode.GALLERY;
            updateModeButtons();
            setStatus(R.string.status_no_camera);
        }
    }

    private void initTextToSpeech() {
        if (textToSpeech != null || destroyed) {
            return;
        }
        textToSpeech = new TextToSpeech(getApplicationContext(), status ->
                runOnUiThread(() -> handleTtsInitialization(status)));
    }

    private void handleTtsInitialization(int status) {
        if (destroyed || textToSpeech == null) {
            return;
        }
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            Logger.w("语音引擎初始化失败，状态码：" + status);
            textToSpeech.shutdown();
            textToSpeech = null;
            if (voiceSwitch.isChecked()) {
                promptInstallTts();
            }
            return;
        }

        textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build());
        textToSpeech.setSpeechRate(0.92f);
        textToSpeech.setPitch(1.0f);

        // 依次尝试简体中文，部分机型只声明 zh 或 zh-CN 中的一种。
        zhTtsReady = trySetLanguage(Locale.SIMPLIFIED_CHINESE)
                || trySetLanguage(Locale.CHINA)
                || trySetLanguage(Locale.CHINESE);
        ttsReady = zhTtsReady
                || trySetLanguage(Locale.getDefault())
                || trySetLanguage(Locale.US);

        if (!ttsReady) {
            if (voiceSwitch.isChecked()) {
                promptInstallTts();
            }
            return;
        }
        Logger.d(zhTtsReady ? "中文语音播报已就绪" : "语音已就绪，将使用英文播报");
        if (voiceSwitch.isChecked()) {
            Toast.makeText(
                    this,
                    zhTtsReady ? R.string.voice_ready_zh : R.string.voice_ready_fallback,
                    Toast.LENGTH_SHORT).show();
            maybeSpeakLatest();
        }
    }

    /**
     * 设备上没有任何可用的语音引擎/语音数据时，弹窗引导用户去系统 TTS 设置下载语音包。
     */
    private void promptInstallTts() {
        if (destroyed || ttsPromptShown) {
            return;
        }
        ttsPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.tts_missing_title)
                .setMessage(R.string.tts_missing_message)
                .setPositiveButton(R.string.tts_go_settings, (dialog, which) -> {
                    try {
                        startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                    } catch (Exception exception) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton(R.string.tts_cancel, null)
                .show();
    }

    private boolean trySetLanguage(Locale locale) {
        if (textToSpeech == null) {
            return false;
        }
        int result = textToSpeech.setLanguage(locale);
        return result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统 TTS 设置返回后自动重新连接，不要求用户再次切换开关。
        if (voiceSwitch != null
                && voiceSwitch.isChecked()
                && !ttsReady
                && textToSpeech == null) {
            initTextToSpeech();
        }
    }

    /**
     * 播报 top-1：相册结果立即播报；实时画面连续稳定三帧后播报，避免抖动和刷屏。
     */
    private void speakRecognition(List<Recognition> results, Mode requestMode) {
        latestCloudDescription = null;
        if (results.isEmpty()) {
            latestTopRecognition = null;
            latestTopMode = null;
            speechGate.resetCandidate();
            return;
        }

        Recognition top = results.get(0);
        latestTopRecognition = top;
        latestTopMode = requestMode;
        String key = speechKey(top);

        speechGate.observe(key, requestMode == Mode.GALLERY);
        maybeSpeakLatest();
    }

    private void maybeSpeakLatest() {
        if (!voiceSwitch.isChecked()
                || !ttsReady
                || textToSpeech == null
                || (latestTopRecognition == null && latestCloudDescription == null)
                || latestTopMode == null) {
            return;
        }
        String key = currentSpeechKey();
        if (!speechGate.isReady(key)) {
            return;
        }
        String announcement;
        if (latestCloudDescription != null) {
            announcement = getString(
                    R.string.tts_cloud_announcement,
                    latestCloudDescription);
        } else {
            String title = zhTtsReady
                    ? latestTopRecognition.getTitleZh()
                    : latestTopRecognition.getTitle();
            announcement = getString(R.string.tts_announcement, title);
        }
        int result = textToSpeech.speak(
                announcement,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "vision-ai-recognition");
        if (result == TextToSpeech.SUCCESS) {
            speechGate.markAnnounced(key);
            ttsFailureNotified = false;
            Logger.d("已提交语音播报：" + announcement);
        } else {
            Logger.w("语音播报提交失败，状态码：" + result);
            if (!ttsFailureNotified) {
                ttsFailureNotified = true;
                Toast.makeText(this, R.string.voice_speak_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String speechKey(Recognition recognition) {
        return recognition.getTitleZh() + '|' + recognition.getTitle();
    }

    private String currentSpeechKey() {
        return latestCloudDescription != null
                ? "qwen|" + latestCloudDescription
                : speechKey(latestTopRecognition);
    }

    /** 千问已经生成完整中文描述，返回后立即播报；相同描述只播报一次。 */
    private void speakCloudRecognition(String description, Mode requestMode) {
        String speechText = sanitizeCloudSpeech(description);
        if (speechText.isEmpty()) {
            latestCloudDescription = null;
            latestTopRecognition = null;
            latestTopMode = null;
            speechGate.resetCandidate();
            return;
        }
        latestTopRecognition = null;
        latestCloudDescription = speechText;
        latestTopMode = requestMode;
        String key = currentSpeechKey();
        speechGate.observe(key, true);
        maybeSpeakLatest();
    }

    private static String sanitizeCloudSpeech(String description) {
        if (description == null) {
            return "";
        }
        return description
                .replace("#", "")
                .replace("*", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void resetSpeechState() {
        speechGate.reset();
        if (latestTopMode != null
                && (latestTopRecognition != null || latestCloudDescription != null)) {
            speechGate.observe(currentSpeechKey(),
                    latestCloudDescription != null || latestTopMode == Mode.GALLERY);
        }
    }

    private void initClassifier() {
        inferenceExecutor.execute(() -> {
            try {
                ImageClassifier loaded = new ImageClassifier(getApplicationContext());
                if (destroyed) {
                    loaded.close();
                    return;
                }
                classifier = loaded;
                classifierReady = true;
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    pickImageButton.setEnabled(true);
                    if (mode == Mode.GALLERY && selectedImageUri != null) {
                        enterGalleryMode(selectedImageUri);
                    } else {
                        renderIdleStatus();
                    }
                });
            } catch (RuntimeException exception) {
                Logger.e("模型加载失败", exception);
                runOnUiThread(() -> {
                    if (!destroyed) {
                        pickImageButton.setEnabled(false);
                        setStatus(R.string.status_model_load_failed, safeMessage(exception));
                    }
                });
            }
        });
    }

    private boolean hasCamera() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void flipCamera() {
        if (mode != Mode.CAMERA || cameraProvider == null) {
            return;
        }
        int targetLens = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        try {
            if (!cameraProvider.hasCamera(cameraSelector(targetLens))) {
                Toast.makeText(this, R.string.camera_flip_unavailable, Toast.LENGTH_SHORT).show();
                updateCameraControls();
                return;
            }
        } catch (CameraInfoUnavailableException exception) {
            Logger.e("查询摄像头失败", exception);
            Toast.makeText(this, R.string.camera_flip_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        lensFacing = targetLens;
        modeGeneration++;
        lastAnalysisTime = 0L;
        resetResultsForModeSwitch();
        bindCameraUseCases(cameraProvider);
        if (cameraBound) {
            Toast.makeText(
                    this,
                    lensFacing == CameraSelector.LENS_FACING_FRONT
                            ? R.string.camera_front_active
                            : R.string.camera_back_active,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static CameraSelector cameraSelector(int targetLensFacing) {
        return new CameraSelector.Builder()
                .requireLensFacing(targetLensFacing)
                .build();
    }

    private void changeZoomByFactor(float factor) {
        if (mode != Mode.CAMERA || boundCamera == null) {
            return;
        }
        ZoomState state = boundCamera.getCameraInfo().getZoomState().getValue();
        if (state == null) {
            return;
        }
        float target = requestedZoomRatio * factor;
        applyZoomRatio(target, state);
    }

    private void applyZoomRatio(float target, ZoomState state) {
        if (boundCamera == null) {
            return;
        }
        requestedZoomRatio = Math.max(
                state.getMinZoomRatio(),
                Math.min(target, state.getMaxZoomRatio()));
        boundCamera.getCameraControl().setZoomRatio(requestedZoomRatio);
        updateZoomUi(requestedZoomRatio, state);
    }

    private void updateZoomUi(float ratio, ZoomState state) {
        zoomRatioText.setText(getString(R.string.zoom_ratio_format, ratio));
        float epsilon = 0.01f;
        zoomOutButton.setEnabled(cameraBound && ratio > state.getMinZoomRatio() + epsilon);
        zoomInButton.setEnabled(cameraBound && ratio < state.getMaxZoomRatio() - epsilon);
        zoomOutButton.setContentDescription(getString(
                R.string.zoom_out_accessibility,
                state.getMinZoomRatio()));
        zoomInButton.setContentDescription(getString(
                R.string.zoom_in_accessibility,
                state.getMaxZoomRatio()));
    }

    private void enterCameraMode() {
        if (!hasCamera()) {
            setStatus(R.string.status_no_camera);
            return;
        }

        mode = Mode.CAMERA;
        modeGeneration++;
        selectedImageUri = null;
        clearSelectedImage();
        latestTopRecognition = null;
        latestCloudDescription = null;
        latestTopMode = null;
        resetSpeechState();
        adapter.updateResults(Collections.emptyList());
        overlayView.clear();
        showEmptyResults(Mode.CAMERA);
        updateModeButtons();
        requestCameraPermissionIfNeeded();
    }

    private void requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraPermissionDenied = false;
            startCameraIfPossible();
        } else {
            setStatus(classifierReady
                    ? R.string.status_camera_permission_required
                    : R.string.status_model_loading);
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraIfPossible() {
        if (destroyed || mode != Mode.CAMERA) {
            return;
        }
        if (classifierReady) {
            setStatus(R.string.status_camera_starting);
        }

        if (cameraProvider != null) {
            bindCameraUseCases(cameraProvider);
            return;
        }

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (!destroyed && mode == Mode.CAMERA) {
                    bindCameraUseCases(cameraProvider);
                }
            } catch (Exception exception) {
                Logger.e("相机初始化失败", exception);
                if (!destroyed && mode == Mode.CAMERA) {
                    setStatus(R.string.status_camera_start_failed, safeMessage(exception));
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider provider) {
        try {
            CameraSelector selector = cameraSelector(lensFacing);
            if (!provider.hasCamera(selector)) {
                int alternateLens = lensFacing == CameraSelector.LENS_FACING_BACK
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK;
                selector = cameraSelector(alternateLens);
                if (!provider.hasCamera(selector)) {
                    throw new IllegalStateException("没有可用的前置或后置摄像头");
                }
                lensFacing = alternateLens;
            }

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                    .setResolutionStrategy(new ResolutionStrategy(
                            ANALYSIS_RESOLUTION,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build();

            imageAnalysis = new ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            imageAnalysis.setAnalyzer(inferenceExecutor, this::analyzeFrame);

            if (boundCamera != null) {
                boundCamera.getCameraInfo().getZoomState().removeObservers(this);
            }
            provider.unbindAll();
            boundCamera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageAnalysis);
            cameraBound = true;
            overlayView.setMirrored(lensFacing == CameraSelector.LENS_FACING_FRONT);
            ZoomState initialZoomState = boundCamera.getCameraInfo().getZoomState().getValue();
            if (initialZoomState != null) {
                applyZoomRatio(requestedZoomRatio, initialZoomState);
            }
            boundCamera.getCameraInfo().getZoomState().observe(this, zoomState -> {
                if (zoomState != null && boundCamera != null) {
                    requestedZoomRatio = zoomState.getZoomRatio();
                    updateZoomUi(requestedZoomRatio, zoomState);
                }
            });
            updateCameraControls();
            renderIdleStatus();
        } catch (Exception exception) {
            cameraBound = false;
            boundCamera = null;
            updateCameraControls();
            Logger.e("相机绑定失败", exception);
            setStatus(R.string.status_camera_start_failed, safeMessage(exception));
        }
    }

    private void stopCamera() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        if (boundCamera != null) {
            boundCamera.getCameraInfo().getZoomState().removeObservers(this);
            boundCamera = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraBound = false;
        updateCameraControls();
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        Bitmap source = null;
        Bitmap upright = null;
        long generation = modeGeneration;
        try {
            long now = SystemClock.elapsedRealtime();
            long interval = isCloudMode() ? CLOUD_ANALYSIS_INTERVAL_MS : ANALYSIS_INTERVAL_MS;
            if (destroyed
                    || mode != Mode.CAMERA
                    || !classifierReady
                    || now - lastAnalysisTime < interval) {
                return;
            }
            lastAnalysisTime = now;

            source = image.toBitmap();
            upright = rotateBitmap(source, image.getImageInfo().getRotationDegrees());
            classifyBitmap(upright, Mode.CAMERA, generation);
        } catch (RuntimeException exception) {
            Logger.e("实时帧处理失败", exception);
            postInferenceError(Mode.CAMERA, generation, exception);
        } finally {
            if (upright != null && upright != source && !upright.isRecycled()) {
                upright.recycle();
            }
            if (source != null && !source.isRecycled()) {
                source.recycle();
            }
            image.close();
        }
    }

    private void enterGalleryMode(Uri uri) {
        selectedImageUri = uri;
        mode = Mode.GALLERY;
        long generation = ++modeGeneration;
        stopCamera();
        latestTopRecognition = null;
        latestCloudDescription = null;
        latestTopMode = null;
        resetSpeechState();
        adapter.updateResults(Collections.emptyList());
        overlayView.clear();
        showEmptyResults(Mode.GALLERY);
        updateModeButtons();
        setStatus(R.string.status_image_loading);

        try {
            inferenceExecutor.execute(() -> classifyPickedImage(uri, generation));
        } catch (RejectedExecutionException ignored) {
            // Activity 已销毁，不再接受新任务。
        }
    }

    private void classifyPickedImage(Uri uri, long generation) {
        Bitmap bitmap = null;
        try {
            bitmap = decodeSampledBitmap(uri, MAX_PICKED_IMAGE_DIMENSION);
            if (bitmap == null) {
                throw new IOException(getString(R.string.error_decode_empty));
            }

            int orientation = readExifOrientation(uri);
            Bitmap upright = applyExifOrientation(bitmap, orientation);
            if (upright != bitmap) {
                bitmap.recycle();
                bitmap = upright;
            }

            if (!isCurrentRequest(Mode.GALLERY, generation)) {
                bitmap.recycle();
                return;
            }

            if (isCloudMode()) {
                Bitmap displayedBitmap = bitmap;
                bitmap = null;
                displayGalleryImage(displayedBitmap, generation);
                runCloudRecognition(displayedBitmap, Mode.GALLERY, generation);
                return;
            }

            long start = SystemClock.uptimeMillis();
            List<Recognition> results = runClassifier(bitmap);
            long elapsed = SystemClock.uptimeMillis() - start;
            Bitmap displayedBitmap = bitmap;
            int imageWidth = bitmap.getWidth();
            int imageHeight = bitmap.getHeight();
            bitmap = null; // 所有权交给主线程的 ImageView
            postResults(results, displayedBitmap, Mode.GALLERY, generation, elapsed,
                    imageWidth, imageHeight);
        } catch (IOException | RuntimeException exception) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            Logger.e("读取或识别图片失败", exception);
            postInferenceError(Mode.GALLERY, generation, exception);
        }
    }

    private void classifyBitmap(Bitmap bitmap, Mode requestMode, long generation) {
        if (isCloudMode()) {
            runCloudRecognition(bitmap, requestMode, generation);
            return;
        }
        try {
            long start = SystemClock.uptimeMillis();
            List<Recognition> results = runClassifier(bitmap);
            long elapsed = SystemClock.uptimeMillis() - start;
            postResults(results, null, requestMode, generation, elapsed,
                    bitmap.getWidth(), bitmap.getHeight());
        } catch (RuntimeException exception) {
            Logger.e("图像识别失败", exception);
            postInferenceError(requestMode, generation, exception);
        }
    }

    private List<Recognition> runClassifier(Bitmap bitmap) {
        if (classifier == null) {
            throw new IllegalStateException(getString(R.string.error_model_not_ready));
        }
        return classifier.detect(bitmap);
    }

    private boolean isCloudMode() {
        return cloudSwitch != null && cloudSwitch.isChecked();
    }

    private CloudRecognizer getCloudRecognizer() {
        if (cloudRecognizer == null) {
            cloudRecognizer = new CloudRecognizer();
        }
        return cloudRecognizer;
    }

    private void runCloudRecognition(Bitmap bitmap, Mode requestMode, long generation) {
        try {
            long start = SystemClock.uptimeMillis();
            String description = getCloudRecognizer().recognize(bitmap);
            long elapsed = SystemClock.uptimeMillis() - start;
            postCloudResult(description, requestMode, generation, elapsed);
        } catch (Exception exception) {
            Logger.e("云端识别失败", exception);
            postInferenceError(requestMode, generation, exception);
        }
    }

    private void postCloudResult(
            String description,
            Mode requestMode,
            long generation,
            long elapsed) {
        runOnUiThread(() -> {
            if (!isCurrentRequest(requestMode, generation)) {
                return;
            }
            adapter.updateResults(Collections.emptyList());
            resultsEmptyState.setVisibility(View.GONE);
            overlayView.clear();
            cloudResultText.setText(description);
            cloudResultScroll.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.GONE);
            speakCloudRecognition(description, requestMode);
            setStatus(R.string.status_cloud_result, elapsed);
        });
    }

    private void displayGalleryImage(Bitmap bitmap, long generation) {
        runOnUiThread(() -> {
            if (isCurrentRequest(Mode.GALLERY, generation)) {
                replaceSelectedImage(bitmap);
            } else if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        });
    }

    private void resetResultsForModeSwitch() {
        adapter.updateResults(Collections.emptyList());
        overlayView.clear();
        cloudResultText.setText("");
        cloudResultScroll.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);
        showEmptyResults(mode);
        latestTopRecognition = null;
        latestCloudDescription = null;
        latestTopMode = null;
        resetSpeechState();
    }

    private void postResults(
            List<Recognition> results,
            Bitmap galleryBitmap,
            Mode requestMode,
            long generation,
            long elapsed,
            int imageWidth,
            int imageHeight) {
        runOnUiThread(() -> {
            if (!isCurrentRequest(requestMode, generation)) {
                if (galleryBitmap != null && !galleryBitmap.isRecycled()) {
                    galleryBitmap.recycle();
                }
                return;
            }

            adapter.updateResults(results);
            resultsEmptyState.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            overlayView.setImageSize(imageWidth, imageHeight);
            overlayView.setDetections(results);
            speakRecognition(results, requestMode);
            if (galleryBitmap != null) {
                replaceSelectedImage(galleryBitmap);
            }
            boolean fallbackClassification = !results.isEmpty()
                    && !results.get(0).hasLocation();
            if (fallbackClassification) {
                setStatus(R.string.status_fallback_result, elapsed);
            } else {
                setStatus(requestMode == Mode.CAMERA
                                ? R.string.status_live_result
                                : R.string.status_gallery_result,
                        elapsed);
            }
        });
    }

    private void postInferenceError(Mode requestMode, long generation, Exception exception) {
        runOnUiThread(() -> {
            if (isCurrentRequest(requestMode, generation)) {
                setStatus(R.string.status_inference_failed, safeMessage(exception));
            }
        });
    }

    private boolean isCurrentRequest(Mode requestMode, long generation) {
        return !destroyed && mode == requestMode && modeGeneration == generation;
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxDimension) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(getString(R.string.error_open_image));
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(getString(R.string.error_invalid_image));
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(getString(R.string.error_open_image));
            }
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    static int calculateInSampleSize(int width, int height, int maxDimension) {
        int sampleSize = 1;
        int longestSide = Math.max(width, height);
        while (longestSide / sampleSize > maxDimension && sampleSize <= Integer.MAX_VALUE / 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private int readExifOrientation(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            ExifInterface exif = new ExifInterface(input);
            return exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException exception) {
            Logger.w("无法读取图片 EXIF 方向，将按原方向显示");
        }
        return ExifInterface.ORIENTATION_NORMAL;
    }

    private static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true);
    }

    private static Bitmap rotateBitmap(Bitmap source, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true);
    }

    private void replaceSelectedImage(Bitmap bitmap) {
        clearSelectedImage();
        overlayView.setMirrored(false);
        selectedBitmap = bitmap;
        selectedImageView.setImageBitmap(bitmap);
        selectedImageView.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
    }

    private void clearSelectedImage() {
        selectedImageView.setImageDrawable(null);
        selectedImageView.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        if (selectedBitmap != null && !selectedBitmap.isRecycled()) {
            selectedBitmap.recycle();
        }
        selectedBitmap = null;
    }

    private void updateModeButtons() {
        liveCameraButton.setEnabled(hasCamera());
        liveCameraButton.setChecked(mode == Mode.CAMERA);
        pickImageButton.setChecked(mode == Mode.GALLERY);
        mediaHint.setText(mode == Mode.CAMERA
                ? R.string.camera_hint
                : R.string.gallery_hint);
        resultsEmptyText.setText(mode == Mode.CAMERA
                ? R.string.results_empty_camera
                : R.string.results_empty_gallery);
        updateCameraControls();
    }

    private void updateCameraControls() {
        if (flipCameraButton == null || cameraZoomControls == null) {
            return;
        }
        boolean cameraMode = mode == Mode.CAMERA;
        boolean canFlip = false;
        if (cameraProvider != null) {
            try {
                canFlip = cameraProvider.hasCamera(
                        cameraSelector(CameraSelector.LENS_FACING_FRONT))
                        && cameraProvider.hasCamera(
                        cameraSelector(CameraSelector.LENS_FACING_BACK));
            } catch (CameraInfoUnavailableException exception) {
                Logger.e("查询可翻转摄像头失败", exception);
            }
        }
        flipCameraButton.setVisibility(cameraMode ? View.VISIBLE : View.GONE);
        flipCameraButton.setEnabled(cameraMode && canFlip);
        flipCameraButton.setContentDescription(getString(
                lensFacing == CameraSelector.LENS_FACING_BACK
                        ? R.string.switch_to_front_camera
                        : R.string.switch_to_back_camera));
        cameraZoomControls.setVisibility(cameraMode ? View.VISIBLE : View.GONE);
        boolean zoomReady = cameraMode && cameraBound && boundCamera != null;
        zoomRatioText.setEnabled(zoomReady);
        if (!zoomReady) {
            zoomOutButton.setEnabled(false);
            zoomInButton.setEnabled(false);
        }
    }

    private void showEmptyResults(Mode emptyMode) {
        resultsEmptyText.setText(emptyMode == Mode.CAMERA
                ? R.string.results_empty_camera
                : R.string.results_empty_gallery);
        resultsEmptyState.setVisibility(View.VISIBLE);
    }

    private void renderIdleStatus() {
        if (destroyed) {
            return;
        }
        if (!classifierReady) {
            setStatus(R.string.status_model_loading);
        } else if (!hasCamera()) {
            setStatus(R.string.status_no_camera);
        } else if (mode == Mode.GALLERY) {
            setStatus(cameraPermissionDenied
                    ? R.string.status_camera_permission_denied
                    : R.string.status_gallery_ready);
        } else if (cameraBound) {
            setStatus(R.string.status_live_ready);
        } else {
            setStatus(R.string.status_camera_starting);
        }
    }

    private void setStatus(int stringResource, Object... formatArgs) {
        statusText.setText(getString(stringResource, formatArgs));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_MODE, mode.name());
        outState.putInt(STATE_LENS_FACING, lensFacing);
        outState.putFloat(STATE_ZOOM_RATIO, requestedZoomRatio);
        if (selectedImageUri != null) {
            outState.putString(STATE_IMAGE_URI, selectedImageUri.toString());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        modeGeneration++;
        stopCamera();
        clearSelectedImage();

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        try {
            inferenceExecutor.execute(() -> {
                if (classifier != null) {
                    classifier.close();
                    classifier = null;
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Executor 已关闭。
        } finally {
            inferenceExecutor.shutdown();
        }
        super.onDestroy();
    }
}
