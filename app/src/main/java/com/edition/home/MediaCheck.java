package com.edition.home;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

/** Verifica se um vídeo pode ser usado como fundo: H.264 (AVC) com no máximo 1280×720. */
public final class MediaCheck {

    private static final int MAX_LONG_SIDE = 1280;
    private static final int MAX_SHORT_SIDE = 720;

    private MediaCheck() {
    }

    public static boolean supportedVideo(Context context, Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (!"video/avc".equals(mime)) {
                        return false;
                    }
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    return Math.max(w, h) <= MAX_LONG_SIDE && Math.min(w, h) <= MAX_SHORT_SIDE;
                }
            }
            return false;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        } finally {
            extractor.release();
        }
    }
}
