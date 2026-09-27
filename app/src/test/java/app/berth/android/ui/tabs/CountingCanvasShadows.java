package app.berth.android.ui.tabs;

import static org.robolectric.util.reflector.Reflector.reflector;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNativeBaseCanvas;
import org.robolectric.shadows.ShadowNativeBaseRecordingCanvas;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import org.robolectric.util.reflector.Static;

/**
 * Robolectric's native canvases, counting what they are handed before they draw it: the glyphs and the
 * bitmaps any code under test gives a software canvas (a window drawn by hand, a bitmap's own canvas) or
 * a recording one (a layer's display list, which the render thread replays every frame it draws), so a
 * test reads what a draw cost however the code under it draws.
 *
 * <p>In Java because a shadow's implementations are static methods standing in for static natives. At
 * SDK 35 those natives are the platform's own, registered by Robolectric's native runtime, so each count
 * calls through to the original; every native not counted here goes to Robolectric's shadows, which these
 * extend.
 */
public final class CountingCanvasShadows {
    private CountingCanvasShadows() {}

    /** Glyphs handed to software canvases. */
    public static long rasterGlyphs;
    /** Bitmaps drawn on software canvases. */
    public static long rasterBitmaps;
    /** Glyphs recorded into display lists. */
    public static long recordedGlyphs;
    /** Bitmaps recorded into display lists. */
    public static long recordedBitmaps;

    public static void reset() {
        rasterGlyphs = 0;
        rasterBitmaps = 0;
        recordedGlyphs = 0;
        recordedBitmaps = 0;
    }

    @Implements(className = "android.graphics.BaseCanvas", isInAndroidSdk = false, callNativeMethodsByDefault = true, minSdk = 35)
    public static class Raster extends ShadowNativeBaseCanvas {
        @Implementation(minSdk = 35)
        protected static void nDrawText(long canvas, char[] text, int index, int count, float x, float y, int flags, long paint) {
            rasterGlyphs += count;
            reflector(RasterNatives.class).nDrawText(canvas, text, index, count, x, y, flags, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawText(long canvas, String text, int start, int end, float x, float y, int flags, long paint) {
            rasterGlyphs += end - start;
            reflector(RasterNatives.class).nDrawText(canvas, text, start, end, x, y, flags, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawTextRun(long canvas, char[] text, int start, int count, int contextStart, int contextCount, float x, float y, boolean rtl, long paint, long measured) {
            rasterGlyphs += count;
            reflector(RasterNatives.class).nDrawTextRun(canvas, text, start, count, contextStart, contextCount, x, y, rtl, paint, measured);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawTextRun(long canvas, String text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean rtl, long paint) {
            rasterGlyphs += end - start;
            reflector(RasterNatives.class).nDrawTextRun(canvas, text, start, end, contextStart, contextEnd, x, y, rtl, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawBitmap(long canvas, long bitmap, float left, float top, long paint, int canvasDensity, int screenDensity, int bitmapDensity) {
            rasterBitmaps++;
            reflector(RasterNatives.class).nDrawBitmap(canvas, bitmap, left, top, paint, canvasDensity, screenDensity, bitmapDensity);
        }
    }

    @Implements(className = "android.graphics.BaseRecordingCanvas", isInAndroidSdk = false, callNativeMethodsByDefault = true, minSdk = 35)
    public static class Recording extends ShadowNativeBaseRecordingCanvas {
        @Implementation(minSdk = 35)
        protected static void nDrawText(long canvas, char[] text, int index, int count, float x, float y, int flags, long paint) {
            recordedGlyphs += count;
            reflector(RecordingNatives.class).nDrawText(canvas, text, index, count, x, y, flags, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawText(long canvas, String text, int start, int end, float x, float y, int flags, long paint) {
            recordedGlyphs += end - start;
            reflector(RecordingNatives.class).nDrawText(canvas, text, start, end, x, y, flags, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawTextRun(long canvas, char[] text, int start, int count, int contextStart, int contextCount, float x, float y, boolean rtl, long paint, long measured) {
            recordedGlyphs += count;
            reflector(RecordingNatives.class).nDrawTextRun(canvas, text, start, count, contextStart, contextCount, x, y, rtl, paint, measured);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawTextRun(long canvas, String text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean rtl, long paint) {
            recordedGlyphs += end - start;
            reflector(RecordingNatives.class).nDrawTextRun(canvas, text, start, end, contextStart, contextEnd, x, y, rtl, paint);
        }

        @Implementation(minSdk = 35)
        protected static void nDrawBitmap(long canvas, long bitmap, float left, float top, long paint, int canvasDensity, int screenDensity, int bitmapDensity) {
            recordedBitmaps++;
            reflector(RecordingNatives.class).nDrawBitmap(canvas, bitmap, left, top, paint, canvasDensity, screenDensity, bitmapDensity);
        }
    }

    @ForType(className = "android.graphics.BaseCanvas")
    interface RasterNatives {
        @Static @Direct
        void nDrawText(long canvas, char[] text, int index, int count, float x, float y, int flags, long paint);

        @Static @Direct
        void nDrawText(long canvas, String text, int start, int end, float x, float y, int flags, long paint);

        @Static @Direct
        void nDrawTextRun(long canvas, char[] text, int start, int count, int contextStart, int contextCount, float x, float y, boolean rtl, long paint, long measured);

        @Static @Direct
        void nDrawTextRun(long canvas, String text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean rtl, long paint);

        @Static @Direct
        void nDrawBitmap(long canvas, long bitmap, float left, float top, long paint, int canvasDensity, int screenDensity, int bitmapDensity);
    }

    @ForType(className = "android.graphics.BaseRecordingCanvas")
    interface RecordingNatives {
        @Static @Direct
        void nDrawText(long canvas, char[] text, int index, int count, float x, float y, int flags, long paint);

        @Static @Direct
        void nDrawText(long canvas, String text, int start, int end, float x, float y, int flags, long paint);

        @Static @Direct
        void nDrawTextRun(long canvas, char[] text, int start, int count, int contextStart, int contextCount, float x, float y, boolean rtl, long paint, long measured);

        @Static @Direct
        void nDrawTextRun(long canvas, String text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean rtl, long paint);

        @Static @Direct
        void nDrawBitmap(long canvas, long bitmap, float left, float top, long paint, int canvasDensity, int screenDensity, int bitmapDensity);
    }
}
