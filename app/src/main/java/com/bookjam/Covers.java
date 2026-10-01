package com.bookjam;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Book covers. The picture in the book's folder if there is one (cover.jpg,
 * folder.jpg, ...), otherwise the art embedded in its first audio files. It is
 * scaled down once and kept as a JPEG in the app's own storage, so the
 * library never has to open the audio again. A book with no art at all gets
 * a gradient with its initials.
 */
final class Covers {

    private static final int MAX = 1024;
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(32 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getAllocationByteCount();
                }
            };

    private Covers() {
    }

    static File file(Context c, long id) {
        return new File(new File(c.getFilesDir(), "covers"), id + ".jpg");
    }

    /** The cover at roughly px across, from memory or disk. Null if the book has none. */
    static Bitmap load(Context c, long id, int px) {
        String key = id + ":" + px;
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        File f = file(c, id);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) CACHE.put(key, b);
        return b;
    }

    /**
     * Shows a book's cover in an image view: the placeholder at once, the
     * picture when it has loaded. tint, if given, gets the cover's main colour.
     */
    static void into(final ImageView v, final Library.Book b, final int px, final IntConsumer tint) {
        final String tag = b.id + ":" + px;
        v.setTag(tag);
        Bitmap hit = CACHE.get(tag);
        if (hit != null) {
            v.setImageBitmap(hit);
            if (tint != null) tint.accept(tint(hit));
            return;
        }
        v.setImageDrawable(new Placeholder(b.name));
        if (tint != null) tint.accept(Placeholder.color(b.name));
        final Context c = v.getContext().getApplicationContext();
        DECODE.execute(() -> {
            final Bitmap bm = load(c, b.id, px);
            if (bm == null) return;
            final int t = tint == null ? 0 : tint(bm);
            MAIN.post(() -> {
                if (!tag.equals(v.getTag())) return;
                v.setImageBitmap(bm);
                if (tint != null) tint.accept(t);
            });
        });
    }

    /** A bitmap for the lock screen and the notification: the cover or the placeholder. */
    static Bitmap art(Context c, Library.Book b) {
        Bitmap bm = load(c, b.id, 512);
        if (bm != null) return bm;
        bm = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Placeholder p = new Placeholder(b.name);
        p.setBounds(0, 0, 512, 512);
        p.draw(new Canvas(bm));
        return bm;
    }

    /** Finds and saves a book's cover. Slow: run it in the background. */
    static boolean build(Context c, long id, Uri tree, String folderDoc, List<Library.Track> tracks) {
        ContentResolver cr = c.getContentResolver();
        Bitmap bm = null;
        Scanner.Entry img = Scanner.coverImage(cr, tree, folderDoc);
        if (img != null) {
            bm = decode(ImageDecoder.createSource(cr,
                    DocumentsContract.buildDocumentUriUsingTree(tree, img.doc)));
        }
        for (int i = 0; bm == null && i < Math.min(3, tracks.size()); i++) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(c, tracks.get(i).uri(tree));
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) bm = decode(ImageDecoder.createSource(ByteBuffer.wrap(pic)));
            } catch (Exception ignored) {
                // no art in this one
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
        if (bm == null) return false;
        File f = file(c, id);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            return false;
        }
        if (!tmp.renameTo(f)) return false;
        for (String k : CACHE.snapshot().keySet()) {
            if (k.startsWith(id + ":")) CACHE.remove(k);
        }
        return true;
    }

    private static Bitmap decode(ImageDecoder.Source src) {
        try {
            return ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int max = Math.max(w, h);
                if (max > MAX) {
                    float k = MAX / (float) max;
                    d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                }
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The colour a cover is mostly made of, leaning to its most vivid parts. */
    static int tint(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 16, 16, true);
        float r = 0, g = 0, bl = 0, sum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getPixel(x, y);
                Color.colorToHSV(p, hsv);
                float w = hsv[1] * hsv[2] + 0.03f;
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                bl += Color.blue(p) * w;
                sum += w;
            }
        }
        if (s != b) s.recycle();
        return Color.rgb(Math.round(r / sum), Math.round(g / sum), Math.round(bl / sum));
    }

    /** A soft two-colour gradient with the book's initials, picked from its name. */
    static final class Placeholder extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letters;
        private final int from, to;

        Placeholder(String name) {
            float hue = hue(name);
            from = Color.HSVToColor(new float[]{hue, 0.42f, 0.80f});
            to = Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.58f, 0.42f});
            letters = initials(name);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Ui.weight(700));
        }

        static int color(String name) {
            return Color.HSVToColor(new float[]{hue(name), 0.5f, 0.62f});
        }

        private static float hue(String name) {
            return (name.hashCode() & 0x7fffffff) % 360;
        }

        static String initials(String name) {
            StringBuilder sb = new StringBuilder();
            for (String w : name.split("[^\\p{L}\\p{N}]+")) {
                if (w.isEmpty()) continue;
                sb.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
                if (sb.length() >= 2) break;
            }
            return sb.length() == 0 ? "♪" : sb.toString();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, from, to,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            paint.setShader(null);
            paint.setColor(0xEEFFFFFF);
            paint.setTextSize(b.height() * (letters.length() > 1 ? 0.32f : 0.40f));
            float y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2f;
            canvas.drawText(letters, b.exactCenterX(), y, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
