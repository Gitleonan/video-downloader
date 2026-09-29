package com.galaxy.downloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Byte-level tests for the MicroVideo (Google Motion Photo V1) composer. */
public class MotionPhotoTest {

    /** Minimal structural JPEG: SOI + JFIF APP0 + EOI (not decodable, but
     * every marker the composer walks is real). */
    private static byte[] fakeJpeg() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xFF); b.write(0xD8);                                  // SOI
        b.write(0xFF); b.write(0xE0); b.write(0x00); b.write(0x10);    // APP0 len 16
        b.write("JFIF\0".getBytes(StandardCharsets.US_ASCII), 0, 5);
        b.write(1); b.write(1); b.write(0); b.write(0); b.write(1);
        b.write(0); b.write(1); b.write(0); b.write(0);
        b.write(0xFF); b.write(0xD9);                                  // EOI
        return b.toByteArray();
    }

    private static byte[] fakeMp4(int size) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); b.write(0); b.write(0); b.write(24);               // box size
        b.write("ftyp".getBytes(StandardCharsets.US_ASCII), 0, 4);
        b.write("isom".getBytes(StandardCharsets.US_ASCII), 0, 4);
        for (int i = b.size(); i < size; i++) b.write(i & 0x7F);
        return b.toByteArray();
    }

    /** Walks JPEG segments from SOI and returns the payload of the first
     * APP1 whose payload starts with the XMP namespace, else null. */
    private static byte[] findXmpSegment(byte[] jpg) {
        int p = 2; // after SOI
        while (p + 4 <= jpg.length) {
            if ((jpg[p] & 0xFF) != 0xFF) return null;
            int marker = jpg[p + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) return null;         // EOI / SOS
            int segLen = ((jpg[p + 2] & 0xFF) << 8) | (jpg[p + 3] & 0xFF);
            if (marker == 0xE1) {
                int nsLen = "http://ns.adobe.com/xap/1.0/".length() + 1;
                if (segLen >= 2 + nsLen) {
                    byte[] ns = new byte[nsLen];
                    System.arraycopy(jpg, p + 4, ns, 0, nsLen);
                    byte[] expect = "http://ns.adobe.com/xap/1.0/\0"
                            .getBytes(StandardCharsets.US_ASCII);
                    if (java.util.Arrays.equals(ns, expect)) {
                        byte[] payload = new byte[segLen - 2 - nsLen];
                        System.arraycopy(jpg, p + 4 + nsLen, payload, 0, payload.length);
                        return payload;
                    }
                }
            }
            p += 2 + segLen;
        }
        return null;
    }

    @Test
    public void isJpeg_detectsSoi() {
        assertTrue(MotionPhoto.isJpeg(fakeJpeg()));
        assertFalse(MotionPhoto.isJpeg(new byte[]{0x42, 0x4D, 0, 0}));   // bmp
        assertFalse(MotionPhoto.isJpeg(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        assertFalse(MotionPhoto.isJpeg(null));
    }

    @Test
    public void compose_injectsXmpAfterSoi_andAppendsVideo() {
        byte[] still = fakeJpeg();
        byte[] video = fakeMp4(1024);
        byte[] out = MotionPhoto.compose(still, video);

        // Output = still-with-XMP + video, byte-identical video tail.
        assertEquals(still.length + video.length + /* APP1 */ 2 + 2
                + "http://ns.adobe.com/xap/1.0/\0".length()
                + MotionPhoto.microVideoXmp(video.length)
                        .getBytes(StandardCharsets.UTF_8).length, out.length);
        byte[] tail = new byte[video.length];
        System.arraycopy(out, out.length - video.length, tail, 0, video.length);
        assertArrayEquals(video, tail);

        // First segment after SOI is our APP1 with the XMP namespace.
        assertEquals(0xFF, out[0] & 0xFF);
        assertEquals(0xD8, out[1] & 0xFF);
        assertEquals(0xFF, out[2] & 0xFF);
        assertEquals(0xE1, out[3] & 0xFF);
        byte[] xmp = findXmpSegment(out);
        assertTrue(xmp != null);
        String x = new String(xmp, StandardCharsets.UTF_8);
        assertTrue(x.contains("GCamera:MicroVideo=\"1\""));
        assertTrue(x.contains("GCamera:MicroVideoVersion=\"1\""));
        // Offset == appended video byte length (readers locate the video at
        // fileSize - offset).
        assertTrue(x.contains("GCamera:MicroVideoOffset=\"" + video.length + "\""));
        assertTrue(x.contains("GCamera:MicroVideoPresentationTimestampUs="));

        // The original JPEG (post-SOI) is preserved after the inserted APP1:
        // its EOI must sit exactly where the video begins.
        int videoStart = out.length - video.length;
        assertEquals((byte) 0xFF, out[videoStart - 2]);
        assertEquals((byte) 0xD9, out[videoStart - 1]);
    }

    @Test
    public void compose_offsetTracksVideoLength_notStillLength() {
        byte[] still = fakeJpeg();
        byte[] video = fakeMp4(5000);
        byte[] out = MotionPhoto.compose(still, video);
        byte[] xmp = findXmpSegment(out);
        String x = new String(xmp, StandardCharsets.UTF_8);
        assertTrue(x.contains("GCamera:MicroVideoOffset=\"5000\""));
        assertFalse(x.contains("GCamera:MicroVideoOffset=\"" + still.length + "\""));
    }

    @Test
    public void compose_rejectsNonJpegStill() {
        assertThrows(IllegalArgumentException.class,
                () -> MotionPhoto.compose(new byte[]{0x42, 0x4D, 0, 0}, fakeMp4(16)));
        assertThrows(IllegalArgumentException.class,
                () -> MotionPhoto.compose(fakeJpeg(), new byte[0]));
    }

    @Test
    public void injectXmp_rejectsOversizedPacket() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 70000; i++) huge.append('a');
        assertThrows(IllegalArgumentException.class,
                () -> MotionPhoto.injectXmp(fakeJpeg(), huge.toString()));
    }

    @Test
    public void findXmpSegment_findsNothingWithoutXmp() {
        assertNull(findXmpSegment(fakeJpeg()));
    }
}
