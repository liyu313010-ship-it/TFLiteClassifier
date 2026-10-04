package com.example.tfliteclassifier.cloud;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 调用阿里云百炼（DashScope）通义千问视觉大模型进行联网识别。
 * 采用 OpenAI 兼容接口，图片转 base64 后随请求一起发送。
 */
public class CloudRecognizer {

    private static final String API_KEY =
            "sk-ws-H.PDXLRIY.3GtA.MEYCIQCDfs5cnIyXwYwenM9DHEbGvJjAJsWG-XT4tR26FmNWewIhAPEX1NBZRyJb-pKB1oiCI1368Iuh2Olg-G_OXG0SjHMt";
    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen3-vl-plus";
    private static final String PROMPT =
            "你是物体识别助手。请识别这张图片中的主要物体，用简体中文简洁回答："
                    + "先列出物体名称（最多 3 个，用顿号分隔），再用一句话描述场景。"
                    + "总共不超过 60 字，不要使用 Markdown、标题或序号，以便直接语音播报。";
    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final int JPEG_QUALITY = 85;

    /**
     * 对一张图片做云端识别，返回模型生成的简体中文描述。
     */
    public String recognize(Bitmap bitmap) throws Exception {
        String base64 = bitmapToBase64(bitmap);
        JSONObject payload = buildPayload(base64);
        String response = postJson(payload);
        return parseContent(response);
    }

    private String bitmapToBase64(Bitmap bitmap) {
        Bitmap scaled = scaleDown(bitmap);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (scaled != bitmap && !scaled.isRecycled()) {
            scaled.recycle();
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap scaleDown(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= MAX_IMAGE_DIMENSION) {
            return source;
        }
        float scale = (float) MAX_IMAGE_DIMENSION / longest;
        return Bitmap.createScaledBitmap(
                source,
                Math.round(width * scale),
                Math.round(height * scale),
                true);
    }

    private JSONObject buildPayload(String base64) throws Exception {
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", PROMPT);

        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/jpeg;base64," + base64);

        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        imageContent.put("image_url", imageUrl);

        JSONArray content = new JSONArray();
        content.put(textContent);
        content.put(imageContent);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        JSONArray messages = new JSONArray();
        messages.put(userMessage);

        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        payload.put("messages", messages);
        return payload;
    }

    private String postJson(JSONObject payload) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(stream);
            if (code >= 200 && code < 300) {
                return response;
            }
            throw new IOException("云端识别失败 HTTP " + code + "：" + response);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String parseContent(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "（云端未返回结果）";
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message.optString("content", "").trim();
        return content.isEmpty() ? "（云端未返回结果）" : content;
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
