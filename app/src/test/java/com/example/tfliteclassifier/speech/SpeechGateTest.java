package com.example.tfliteclassifier.speech;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeechGateTest {

    @Test
    public void cameraResultMustBeStableBeforeAnnouncement() {
        SpeechGate gate = new SpeechGate(3);

        gate.observe("杯子|cup", false);
        assertFalse(gate.isReady("杯子|cup"));
        gate.observe("杯子|cup", false);
        assertFalse(gate.isReady("杯子|cup"));
        gate.observe("杯子|cup", false);
        assertTrue(gate.isReady("杯子|cup"));
    }

    @Test
    public void galleryResultCanBeAnnouncedImmediately() {
        SpeechGate gate = new SpeechGate(3);

        gate.observe("键盘|keyboard", true);

        assertTrue(gate.isReady("键盘|keyboard"));
    }

    @Test
    public void announcedResultDoesNotRepeatUntilResultChanges() {
        SpeechGate gate = new SpeechGate(2);
        gate.observe("手机|cell phone", false);
        gate.observe("手机|cell phone", false);
        gate.markAnnounced("手机|cell phone");

        gate.observe("手机|cell phone", false);
        assertFalse(gate.isReady("手机|cell phone"));
        gate.observe("书|book", false);
        gate.observe("书|book", false);
        assertTrue(gate.isReady("书|book"));
    }
}
