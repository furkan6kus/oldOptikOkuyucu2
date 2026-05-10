package com.testplus.app.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Kamera önizlemesi üzerine ÇERÇEVE rehberi çizer.
 * Kullanıcı optik formu bu A4 oranlı dikdörtgenin içine oturtmaya çalışır.
 *
 *  ┌────────────────┐  ← dış kısım yarı saydam siyah ile karartılır
 *  │  ▢  ▒▒▒▒  ▢   │     (kullanıcı kadrajı net görsün)
 *  │  ▒▒▒▒▒▒▒▒▒▒   │
 *  │  ▢  ▒▒▒▒  ▢   │  ← 4 köşede L-şeklinde tespit göstergeleri
 *  └────────────────┘
 *  Tespit edilen markerlar yeşil, eksikler kırmızı.
 *  Ortada büyük geri sayım sayısı (3,2,1) auto-capture için.
 */
public class AlignmentOverlayView extends View {

    /** A4 portrait oranı: 210 / 297 ≈ 0.707 */
    private static final float A4_RATIO = 210f / 297f;
    /** Kadraj ekran kenarlarından bu kadar boşluk bıraksın (oran). */
    private static final float MARGIN_RATIO = 0.015f;

    private final Paint guidePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameOkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameBadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countdownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final boolean[] markerOk = new boolean[4]; // TL, TR, BL, BR
    private String statusText = "Optik formu görünür tutun";
    private int countdown = -1;
    private String processingText = null;

    /** Tespit edilen marker'ların ekran-uzayındaki konumları (TL, TR, BL, BR). */
    private final PointF[] detectedScreenPos = new PointF[4];

    /** Cihaz eğim açıları (derece). 0 = telefonu kağıda paralel tut. */
    private float pitchDeg = 0f;
    private float rollDeg = 0f;
    private boolean tiltAvailable = false;
    /** Bu eşiğin altında "yeterince düz" sayılır (pitch ve roll mutlak değer). Otomatik okuma için 3°. */
    private static final float TILT_OK_DEG = 3f;

    public AlignmentOverlayView(Context c) { super(c); init(); }
    public AlignmentOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    public AlignmentOverlayView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(4));

        frameOkPaint.setStyle(Paint.Style.STROKE);
        frameOkPaint.setStrokeWidth(dp(2.5f));
        frameOkPaint.setColor(0x884CAF50); // daha soluk yeşil

        frameBadPaint.setStyle(Paint.Style.STROKE);
        frameBadPaint.setStrokeWidth(dp(2.5f));
        frameBadPaint.setColor(0x66FFFFFF); // daha soluk beyaz

        dotPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0xCC000000);

        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(0x66000000); // dış alan karartma (daha hafif)

        countdownPaint.setColor(0xFF4CAF50);
        countdownPaint.setTextSize(dp(120));
        countdownPaint.setTextAlign(Paint.Align.CENTER);
        countdownPaint.setFakeBoldText(true);
        countdownPaint.setShadowLayer(dp(6), 0, dp(2), 0xCC000000);
    }

    /** Marker merkezleri yeşil çerçeve köşelerine yeterince yakın mı (KagitOkuActivity hesaplar). */
    private boolean guideCornersAligned = true;
    /** Önizlemede kağıt üzerinde belirgin gölge / düzensiz ışık — geri sayım ve okuma kapalı. */
    private boolean previewShadowBlocked = false;

    private void refreshMainStatus() {
        if (processingText != null) return;
        int found = countDetected();
        if (!tiltOk()) {
            statusText = "Telefonu düz tutun (eğim bozuk)";
            return;
        }
        if (previewShadowBlocked) {
            statusText = "Gölge veya düzensiz ışık — geri sayım yok, okuma yok. Işığı düzeltin";
            return;
        }
        if (found == 4 && !guideCornersAligned) {
            statusText = "Kağıt görünür kalsın, rehber çerçeve isteğe bağlı";
            return;
        }
        if (found == 4) {
            statusText = countdown > 0
                ? "Hazır! " + countdown + " — eğim + köşe sabit (3 sn)"
                : "4 köşe + eğim tamam — 3 sn sabit tutun";
            return;
        }
        if (found == 0) {
            statusText = "Kağıdın 4 köşe işareti görünsün";
            return;
        }
        statusText = found + "/4 köşe bulundu — ışık ve açıyı düzeltin";
    }

    /** Köşe blob merkezleri rehber çerçeveye yakın mı — yakın değilse geri sayım başlamaz. */
    public void setGuideCornersAligned(boolean aligned) {
        if (guideCornersAligned == aligned) return;
        guideCornersAligned = aligned;
        refreshMainStatus();
        invalidate();
    }

    public void setPreviewShadowBlocked(boolean blocked) {
        if (previewShadowBlocked == blocked) return;
        previewShadowBlocked = blocked;
        refreshMainStatus();
        invalidate();
    }

    public boolean isPreviewShadowBlocked() {
        return previewShadowBlocked;
    }

    public boolean areGuideCornersAligned() {
        return guideCornersAligned;
    }

    public void setDetectedCorners(boolean tl, boolean tr, boolean bl, boolean br) {
        markerOk[0] = tl; markerOk[1] = tr;
        markerOk[2] = bl; markerOk[3] = br;
        refreshMainStatus();
        invalidate();
    }

    public void setCountdown(int seconds) {
        if (this.countdown == seconds) return;
        this.countdown = seconds;
        refreshMainStatus();
        invalidate();
    }

    public void setProcessingText(String text) {
        this.processingText = text;
        invalidate();
    }

    /**
     * Cihazın eğimini güncelle (derece cinsinden).
     * pitch: telefonu öne/arkaya yatırma; roll: sağa/sola yatırma.
     * Telefonu yere paralel kağıda dik tutuyorsa ikisi de ~0 olmalı.
     */
    public void setTilt(float pitchDeg, float rollDeg) {
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
        this.tiltAvailable = true;
        refreshMainStatus();
        invalidate();
    }

    /** Eğim toleranslı seviyede mi? */
    public float pitchDeg() { return pitchDeg; }
    public float rollDeg()  { return rollDeg; }

    public boolean tiltOk() {
        if (!tiltAvailable) return true;
        return Math.abs(pitchDeg) <= TILT_OK_DEG && Math.abs(rollDeg) <= TILT_OK_DEG;
    }

    /**
     * Tespit edilen marker'ların EKRAN-uzayındaki konumlarını güncelle.
     * Sıra: TL, TR, BL, BR. null geçilirse o köşe çizilmez.
     */
    public void setDetectedScreenPositions(PointF tl, PointF tr, PointF bl, PointF br) {
        detectedScreenPos[0] = tl;
        detectedScreenPos[1] = tr;
        detectedScreenPos[2] = bl;
        detectedScreenPos[3] = br;
        invalidate();
    }

    public boolean allOk() {
        return markerOk[0] && markerOk[1] && markerOk[2] && markerOk[3];
    }

    /**
     * Otomatik / manuel çekim için: eğim + 4 köşe + gölge yok.
     * Rehber çerçeve hizası yalnızca görsel öneridir; çekimi bloklamaz.
     */
    public boolean isReadyForCapture() {
        return tiltOk() && allOk() && !previewShadowBlocked;
    }

    private int countDetected() {
        int n = 0;
        for (boolean b : markerOk) if (b) n++;
        return n;
    }

    /**
     * Ekran ortasında A4 oranlı dikdörtgeni hesaplar.
     * Genişlik / yükseklik ekrana göre maksimum sığacak şekilde ayarlanır.
     */
    public RectF getFrameRect() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return new RectF();

        float maxW = w * (1f - 2f * MARGIN_RATIO);
        float maxH = h * (1f - 2f * MARGIN_RATIO) - dp(20); // alt pay daha küçük

        // A4 portrait: width / height = A4_RATIO
        // Önce yüksekliğe göre genişlik hesapla, sığmıyorsa genişliğe göre yükseklik
        float frameH = maxH;
        float frameW = frameH * A4_RATIO;
        if (frameW > maxW) {
            frameW = maxW;
            frameH = frameW / A4_RATIO;
        }
        float left = (w - frameW) / 2f;
        float top  = (h - frameH) / 2f - dp(6);
        return new RectF(left, top, left + frameW, top + frameH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        RectF frame = getFrameRect();
        if (frame.width() <= 0 || frame.height() <= 0) return;

        // ── Dış alanı karart (kadraj efekti) ──
        // Path: tüm ekran - dikdörtgen iç bölgesi
        Path outside = new Path();
        outside.addRect(0, 0, w, h, Path.Direction.CW);
        outside.addRect(frame, Path.Direction.CCW);
        canvas.drawPath(outside, dimPaint);

        // ── Çerçeve sınırı: yeşile dönmesi için 4 köşe + eğim + gölge yok.
        // Rehber çerçeveye oturtma zorunlu değil ({@link #isReadyForCapture} ile aynı mantık).
        boolean readyVisual = allOk() && tiltOk() && !previewShadowBlocked;
        Paint framePaint = readyVisual ? frameOkPaint : frameBadPaint;
        canvas.drawRect(frame, framePaint);

        // ── 4 köşede L-şeklinde marker göstergeleri (dikdörtgenin köşelerinde) ──
        float armLen = Math.min(frame.width(), frame.height()) * 0.10f;
        float strokeW = dp(5);
        guidePaint.setStrokeWidth(strokeW);
        // i = 0:TL, 1:TR, 2:BL, 3:BR
        for (int i = 0; i < 4; i++) {
            int color = markerOk[i] ? 0xCC4CAF50 : 0xCCFF8A80;
            guidePaint.setColor(color);
            float cx, cy, dx, dy;
            switch (i) {
                case 0: cx = frame.left;  cy = frame.top;    dx = +1; dy = +1; break;
                case 1: cx = frame.right; cy = frame.top;    dx = -1; dy = +1; break;
                case 2: cx = frame.left;  cy = frame.bottom; dx = +1; dy = -1; break;
                default:cx = frame.right; cy = frame.bottom; dx = -1; dy = -1; break;
            }
            canvas.drawLine(cx, cy, cx + dx * armLen, cy, guidePaint);
            canvas.drawLine(cx, cy, cx, cy + dy * armLen, guidePaint);

            // Tespit edildiğinde köşe noktasına nokta koy
            if (markerOk[i]) {
                dotPaint.setColor(0xFF4CAF50);
                canvas.drawCircle(cx, cy, dp(9), dotPaint);
                dotPaint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, dp(3.5f), dotPaint);
            }
        }

        // ── Status metni (alt orta) ──
        String shown = processingText != null ? processingText : statusText;
        float ty = h - dp(34);
        float textW = textPaint.measureText(shown);
        float pad = dp(14);
        canvas.drawRoundRect(
            w / 2f - textW / 2f - pad,
            ty - dp(22),
            w / 2f + textW / 2f + pad,
            ty + dp(10),
            dp(10), dp(10), shadowPaint);
        canvas.drawText(shown, w / 2f, ty, textPaint);

        // ── Üstte küçük ipucu metni (yalnız 0 köşe bulunduğunda; tilt göstergesi yoksa) ──
        if (processingText == null && countDetected() == 0 && !tiltAvailable) {
            String hint = "Rehber çerçeve sadece öneridir";
            float hintW = textPaint.measureText(hint);
            float hy = frame.top - dp(18);
            canvas.drawRoundRect(
                w / 2f - hintW / 2f - dp(10),
                hy - dp(20),
                w / 2f + hintW / 2f + dp(10),
                hy + dp(8),
                dp(8), dp(8), shadowPaint);
            canvas.drawText(hint, w / 2f, hy, textPaint);
        }

        // ── Geri sayım sayısı (ekran ortasında büyük) ──
        // Sadece üç koşul birden tamamsa göster: 4 köşe + eğim OK + rehbere oturmuş.
        if (processingText == null && countdown > 0 && readyVisual) {
            canvas.drawText(String.valueOf(countdown),
                w / 2f, h / 2f + dp(40), countdownPaint);
        }

        // ── Tespit edilen marker'ların ekran konumlarında dolgu daire ──
        // Yeşil = rehbere oturmuş; turuncu = bulunmuş ama köşelere uzak (kullanıcı kaydırmalı).
        int dotFill = guideCornersAligned ? 0xFF4CAF50 : 0xFFFFC107;
        for (int i = 0; i < 4; i++) {
            PointF p = detectedScreenPos[i];
            if (p == null) continue;
            dotPaint.setColor(0xCC000000);
            canvas.drawCircle(p.x, p.y, dp(11), dotPaint);
            dotPaint.setColor(dotFill);
            canvas.drawCircle(p.x, p.y, dp(8), dotPaint);
            dotPaint.setColor(Color.WHITE);
            canvas.drawCircle(p.x, p.y, dp(2.5f), dotPaint);
        }

        // ── Tilt level (su düzeci) — sağ-üst köşede ──
        // İşleme/çekim mesajı varken gizleriz (kullanıcı zaten odaklanmış olur).
        if (tiltAvailable && processingText == null) {
            drawTiltLevel(canvas, w);
        }
    }

    /**
     * Üst-orta konumda büyük bir bubble level (su düzeci) çizer.
     * Eğim 8°'nin altındayken yeşil, üzerindeyken kırmızı yanıp söner.
     * Kabarcık merkezi yakaladıkça kullanıcı çekim için doğru pozisyonu bulmuş olur.
     */
    private void drawTiltLevel(Canvas canvas, int w) {
        // Gölge varken de çekim yok — su terazisi kırmızı kalsın (yanıltıcı yeşil olmasın).
        boolean ok = tiltOk() && !previewShadowBlocked;
        float radius = dp(54);
        float cx = w / 2f;
        float cy = radius + dp(24);

        // Dış glow halkası (eğim bozukken pulse) — büyük + saydam, kırmızı/yeşil
        long t = System.currentTimeMillis();
        float pulse = ok ? 0f : (float) (0.5 + 0.5 * Math.sin(t / 220.0));
        int glowAlpha = ok ? 0x55 : (0x55 + (int)(pulse * 0x80));
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(6));
        int baseColor = ok ? 0x4CAF50 : 0xFF5252;
        glow.setColor((glowAlpha << 24) | baseColor);
        canvas.drawCircle(cx, cy, radius + dp(6), glow);

        // Şeffaf siyah arka plan dolgusu
        Paint bgRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgRing.setStyle(Paint.Style.FILL);
        bgRing.setColor(0xB3000000);
        canvas.drawCircle(cx, cy, radius, bgRing);

        // Dış kontur
        Paint ringStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringStroke.setStyle(Paint.Style.STROKE);
        ringStroke.setStrokeWidth(dp(2.5f));
        ringStroke.setColor(ok ? 0xCC4CAF50 : 0xCCFF5252);
        canvas.drawCircle(cx, cy, radius, ringStroke);

        // Hedef halka (orta — kabarcığın oturması gereken alan)
        Paint targetRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetRing.setStyle(Paint.Style.STROKE);
        targetRing.setStrokeWidth(dp(2));
        targetRing.setColor(0xFFFFFFFF);
        float targetR = dp(13);
        canvas.drawCircle(cx, cy, targetR, targetRing);

        // Çapraz çizgiler (referans)
        targetRing.setStrokeWidth(dp(1));
        targetRing.setColor(0x77FFFFFF);
        canvas.drawLine(cx - radius * 0.78f, cy, cx + radius * 0.78f, cy, targetRing);
        canvas.drawLine(cx, cy - radius * 0.78f, cx, cy + radius * 0.78f, targetRing);

        // Hareketli kabarcık (eğime göre kayar)
        // Maksimum 25 derece eğimde halkanın kenarına ulaşsın
        float maxDeg = 25f;
        float dx = clamp(rollDeg / maxDeg, -1f, 1f) * (radius - dp(8));
        float dy = clamp(pitchDeg / maxDeg, -1f, 1f) * (radius - dp(8));
        // Pitch yönünü ters çevir: telefon öne yatınca kabarcık aşağı kaysın
        dy = -dy;

        Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubble.setStyle(Paint.Style.FILL);
        bubble.setColor(ok ? 0xFF4CAF50 : 0xFFFF5252);
        canvas.drawCircle(cx + dx, cy + dy, dp(10), bubble);
        bubble.setColor(0x99FFFFFF);
        canvas.drawCircle(cx + dx, cy + dy, dp(10), bubble);

        // Etiket — eğim açısı + durum
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setColor(ok ? 0xFFB9F6CA : 0xFFFFCDD2);
        lbl.setTextSize(dp(12));
        lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setFakeBoldText(true);
        String degText = String.format(java.util.Locale.US, "%.0f° / %.0f°",
            Math.abs(pitchDeg), Math.abs(rollDeg));
        canvas.drawText(degText, cx, cy + radius + dp(16), lbl);

        // İkinci satır: durum metni
        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(0xFFFFFFFF);
        sub.setTextSize(dp(11));
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setFakeBoldText(true);
        String subMsg;
        if (previewShadowBlocked) {
            subMsg = tiltOk() ? "ÖNCE IŞIĞI DÜZELT" : "EĞİM VE IŞIK";
        } else {
            subMsg = tiltOk() ? "EĞİM TAMAM" : "EĞİMİ DÜZELT";
        }
        canvas.drawText(subMsg, cx, cy + radius + dp(31), sub);
        if (!ok) postInvalidateOnAnimation(); // pulse animasyonu için
    }

    private float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
