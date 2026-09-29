package com.galaxy.downloader;

/**
 * Google Motion Photo (MicroVideo V1) composer.
 *
 * A motion photo is a valid JPEG whose tail is followed by a raw mp4. The
 * XMP packet (in an APP1 segment near the front of the file) carries
 * GCamera:MicroVideo=1 and MicroVideoOffset — the byte length of the
 * appended video — which readers use to locate the video start as
 * (fileSize - MicroVideoOffset).
 *
 * Pure byte manipulation on purpose: BitmapFactory transcoding of non-JPEG
 * stills lives in MainActivity (Android-only), while everything here runs
 * under plain JUnit.
 */
public final class MotionPhoto {

    private static final byte[] XMP_NAMESPACE =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    /** GCam's conventional default: motion starts 0.5s into the video. */
    private static final String PRESENTATION_US = "500000";

    private MotionPhoto() {}

    /** True when the bytes start with a JPEG SOI marker. */
    public static boolean isJpeg(byte[] b) {
        return b != null && b.length >= 4
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /**
     * Builds the MicroVideo XMP packet for a still whose appended video is
     * {@code videoLength} bytes.
     */
    public static String microVideoXmp(long videoLength) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\""
                + " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
                + " GCamera:MicroVideo=\"1\""
                + " GCamera:MicroVideoVersion=\"1\""
                + " GCamera:MicroVideoOffset=\"" + videoLength + "\""
                + " GCamera:MicroVideoPresentationTimestampUs=\"" + PRESENTATION_US + "\"/>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>";
    }

    /**
     * Inserts an APP1 segment carrying {@code xmp} right after the SOI
     * marker. Readers scan APPn markers for the XMP namespace, so leading
     * position is both valid and what GCam itself produces. Throws when the
     * payload would overflow the segment's 16-bit length field.
     */
    public static byte[] injectXmp(byte[] jpeg, String xmp) {
        if (!isJpeg(jpeg)) {
            throw new IllegalArgumentException("not a JPEG (missing SOI)");
        }
        byte[] packet = xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // APP1 length field counts itself: 2 (length) + 29 (namespace+NUL) + packet
        int segLen = 2 + XMP_NAMESPACE.length + packet.length;
        if (segLen > 0xFFFF) {
            throw new IllegalArgumentException("XMP too large for one APP1 segment");
        }
        byte[] out = new byte[jpeg.length + 2 + segLen];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        int p = 2;
        out[p++] = (byte) 0xFF;
        out[p++] = (byte) 0xE1;
        out[p++] = (byte) (segLen >> 8);
        out[p++] = (byte) (segLen & 0xFF);
        System.arraycopy(XMP_NAMESPACE, 0, out, p, XMP_NAMESPACE.length);
        p += XMP_NAMESPACE.length;
        System.arraycopy(packet, 0, out, p, packet.length);
        p += packet.length;
        System.arraycopy(jpeg, 2, out, p, jpeg.length - 2);
        return out;
    }

    /**
     * Full composition: JPEG still (XMP injected) followed by the mp4 bytes.
     * The still must already be a JPEG — the caller transcodes webp/heif
     * stills through BitmapFactory first on device.
     */
    public static byte[] compose(byte[] stillJpeg, byte[] video) {
        if (video == null || video.length == 0) {
            throw new IllegalArgumentException("empty video");
        }
        byte[] withXmp = injectXmp(stillJpeg, microVideoXmp(video.length));
        byte[] out = new byte[withXmp.length + video.length];
        System.arraycopy(withXmp, 0, out, 0, withXmp.length);
        System.arraycopy(video, 0, out, withXmp.length, video.length);
        return out;
    }
}
