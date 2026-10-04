package com.example.tfliteclassifier.speech;

/**
 * 与 Android TTS 无关的播报节流器：实时结果稳定若干次后放行，并避免重复播报。
 */
public final class SpeechGate {

    private final int requiredObservations;
    private String candidate = "";
    private int observations;
    private String lastAnnounced = "";

    public SpeechGate(int requiredObservations) {
        if (requiredObservations < 1) {
            throw new IllegalArgumentException("稳定次数必须大于 0");
        }
        this.requiredObservations = requiredObservations;
    }

    public void observe(String key, boolean immediate) {
        if (key == null || key.isEmpty()) {
            resetCandidate();
            return;
        }
        if (key.equals(candidate)) {
            observations++;
        } else {
            candidate = key;
            observations = 1;
        }
        if (immediate) {
            observations = requiredObservations;
        }
    }

    public boolean isReady(String key) {
        return key != null
                && key.equals(candidate)
                && observations >= requiredObservations
                && !key.equals(lastAnnounced);
    }

    public void markAnnounced(String key) {
        if (key != null && key.equals(candidate)) {
            lastAnnounced = key;
        }
    }

    public void resetCandidate() {
        candidate = "";
        observations = 0;
    }

    public void reset() {
        resetCandidate();
        lastAnnounced = "";
    }
}
