package com.testplus.app.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Çekilen görüntüyü tam ekrana yakın gösterir; 4 sürüklenebilir köşe ile dörtgen
 * kesim alanı belirlenir. Köşe sırası: 0=TL, 1=TR, 2=BR, 3=BL.
 *
 * <p>Kullanım:
 * <pre>
 *   cropView.setBitmapAndCorners(bitmap, cornersInBitmapSpace);
 *   PointF[] adjusted = cropView.getCornersInBitmapSpace();
 * </pre>
 */
public class DocumentCropView extends View {

    private static final float HANDLE_RADIUS_DP = 20f;
    private static final float HANDLE_TOUCH_DP  = 40f; // dokunma toleransı
    private static final float LINE_WIDTH_DP    = 2.5f;

    private final Paint imagePaint  = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap bitmap;

    // Köşeler bitmap piksel uzayında saklanır: [TL, TR, BR, BL]
    private final PointF[] bitmapCorners = new PointF[4];
    private boolean cornersSet = false;

    // Dokunma ile sürüklenen köşe indeksi (-1 = hiçbiri)
    private int dragIdx = -1;

    // Çizim dönüşümü (bitmap → view piksel): her onDraw öncesi hesaplanır
    private float drawScale  = 1f;
    private float drawOffX   = 0f;
    private float drawOffY   = 0f;
    private boolean transformReady = false;

    public DocumentCropView(Context c) { super(c); init(c); }
    public DocumentCropView(Context c, AttributeSet a) { super(c, a); init(c); }
    public DocumentCropView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        float density = c.getResources().getDisplayMetrics().density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(0xFF00BCD4);   // cyan
        linePaint.setStrokeWidth(LINE_WIDTH_DP * density);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x2200BCD4);   // hafif cyan dolgu

        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF00BCD4);

        handleRingPaint.setStyle(Paint.Style.STROKE);
        handleRingPaint.setColor(Color.WHITE);
        handleRingPaint.setStrokeWidth(2f * density);

        setClickable(true);
        setFocusable(true);
    }

    /** Bitmap ve başlangıç köşelerini ayarlar. corners null ise görüntü sınırlarından oluşturulur. */
    public void setBitmapAndCorners(Bitmap bmp, PointF[] cornersInBitmapSpace) {
        this.bitmap = bmp;
        transformReady = false;
        if (bmp == null) {
            cornersSet = false;
            invalidate();
            return;
        }
        if (cornersInBitmapSpace != null && cornersInBitmapSpace.length == 4
                && cornersInBitmapSpace[0] != null && cornersInBitmapSpace[1] != null
                && cornersInBitmapSpace[2] != null && cornersInBitmapSpace[3] != null) {
            for (int i = 0; i < 4; i++) {
                bitmapCorners[i] = new PointF(cornersInBitmapSpace[i].x, cornersInBitmapSpace[i].y);
            }
            cornersSet = true;
        } else {
            setDefaultCorners(bmp.getWidth(), bmp.getHeight());
        }
        invalidate();
    }

    /** Bitmap değiştirmeden yalnızca köşeleri günceller. */
    public void setCorners(PointF[] cornersInBitmapSpace) {
        if (bitmap == null) return;
        if (cornersInBitmapSpace != null && cornersInBitmapSpace.length == 4
                && cornersInBitmapSpace[0] != null && cornersInBitmapSpace[1] != null
                && cornersInBitmapSpace[2] != null && cornersInBitmapSpace[3] != null) {
            for (int i = 0; i < 4; i++) {
                bitmapCorners[i] = new PointF(cornersInBitmapSpace[i].x, cornersInBitmapSpace[i].y);
            }
            cornersSet = true;
        } else {
            setDefaultCorners(bitmap.getWidth(), bitmap.getHeight());
        }
        invalidate();
    }

    /** Mevcut (muhtemelen kullanıcının ayarladığı) köşeleri bitmap piksel uzayında döndürür. */
    public PointF[] getCornersInBitmapSpace() {
        if (!cornersSet || bitmap == null) return null;
        PointF[] result = new PointF[4];
        for (int i = 0; i < 4; i++) {
            result[i] = new PointF(bitmapCorners[i].x, bitmapCorners[i].y);
        }
        return result;
    }

    // ─── Çizim ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;

        computeDrawTransform();

        // Bitmap'i çiz
        Matrix mx = new Matrix();
        mx.setScale(drawScale, drawScale);
        mx.postTranslate(drawOffX, drawOffY);
        canvas.drawBitmap(bitmap, mx, imagePaint);

        if (!cornersSet) return;

        // Bitmap köşeleri → view koordinatlarına
        PointF tl = bmpToView(bitmapCorners[0]);
        PointF tr = bmpToView(bitmapCorners[1]);
        PointF br = bmpToView(bitmapCorners[2]);
        PointF bl = bmpToView(bitmapCorners[3]);

        // Dolgu alanı
        Path path = new Path();
        path.moveTo(tl.x, tl.y);
        path.lineTo(tr.x, tr.y);
        path.lineTo(br.x, br.y);
        path.lineTo(bl.x, bl.y);
        path.close();
        canvas.drawPath(path, fillPaint);

        // Kenar çizgileri
        canvas.drawPath(path, linePaint);

        // Köşe handles
        float density = getResources().getDisplayMetrics().density;
        float hr = HANDLE_RADIUS_DP * density;
        PointF[] viewCorners = {tl, tr, br, bl};
        for (PointF p : viewCorners) {
            canvas.drawCircle(p.x, p.y, hr, handlePaint);
            canvas.drawCircle(p.x, p.y, hr, handleRingPaint);
        }
    }

    // ─── Dokunma ─────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!cornersSet || bitmap == null) return false;
        float density = getResources().getDisplayMetrics().density;
        float touchR = HANDLE_TOUCH_DP * density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragIdx = findNearestCorner(event.getX(), event.getY(), touchR);
                return dragIdx >= 0;

            case MotionEvent.ACTION_MOVE:
                if (dragIdx < 0) return false;
                moveCorner(dragIdx, event.getX(), event.getY());
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragIdx = -1;
                return true;
        }
        return false;
    }

    // ─── Yardımcı metodlar ───────────────────────────────────────────────────

    private void computeDrawTransform() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            transformReady = false;
            return;
        }
        float vw = getWidth();
        float vh = getHeight();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        drawScale = Math.min(vw / bw, vh / bh);
        drawOffX  = (vw - bw * drawScale) / 2f;
        drawOffY  = (vh - bh * drawScale) / 2f;
        transformReady = true;
    }

    private PointF bmpToView(PointF p) {
        return new PointF(p.x * drawScale + drawOffX, p.y * drawScale + drawOffY);
    }

    private PointF viewToBmp(float vx, float vy) {
        if (!transformReady || drawScale == 0) return new PointF(0, 0);
        return new PointF((vx - drawOffX) / drawScale, (vy - drawOffY) / drawScale);
    }

    private int findNearestCorner(float vx, float vy, float maxDist) {
        computeDrawTransform();
        int best = -1;
        float bestDist2 = maxDist * maxDist;
        for (int i = 0; i < 4; i++) {
            if (bitmapCorners[i] == null) continue;
            PointF vp = bmpToView(bitmapCorners[i]);
            float dx = vp.x - vx;
            float dy = vp.y - vy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestDist2) { bestDist2 = d2; best = i; }
        }
        return best;
    }

    private void moveCorner(int idx, float vx, float vy) {
        if (bitmap == null) return;
        PointF bmp = viewToBmp(vx, vy);
        // Bitmap sınırları içinde tut (küçük marj ile)
        float margin = 5f;
        bmp.x = Math.max(margin, Math.min(bitmap.getWidth() - margin, bmp.x));
        bmp.y = Math.max(margin, Math.min(bitmap.getHeight() - margin, bmp.y));
        bitmapCorners[idx].set(bmp.x, bmp.y);
    }

    private void setDefaultCorners(int bw, int bh) {
        float insetX = bw * 0.05f;
        float insetY = bh * 0.05f;
        bitmapCorners[0] = new PointF(insetX,      insetY);       // TL
        bitmapCorners[1] = new PointF(bw - insetX, insetY);       // TR
        bitmapCorners[2] = new PointF(bw - insetX, bh - insetY);  // BR
        bitmapCorners[3] = new PointF(insetX,      bh - insetY);  // BL
        cornersSet = true;
    }
}
