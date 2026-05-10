package com.testplus.app.utils;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.Log;
import com.testplus.app.database.entities.OptikFormAlan;
import java.util.*;

/**
 * Optik form okuma motoru — birleştirilmiş sürüm.
 * Aşamalar:
 *   1. Görselde KAĞIDIN sınırlarını bul (parlak satır/sütun histogramı).
 *      → Kot, masa, gölge gibi koyu zeminler kağıdın dışında kalır.
 *   2. Kağıt içinde "white point" hesapla — loş fotoğrafları normalize eder.
 *   3. Sadece kağıt içinde, 4 köşedeki siyah kareleri ara
 *      (boyut sınırlaması: ne çok küçük ne çok büyük dark blob).
 *   4. Köşeler "makul dikdörtgen" oluşturuyorsa homografi kur,
 *      yoksa kağıt-sınırlı lineer ölçeğe düş.
 *   5. Hem cevap alanları hem de bilgi alanları (Ad Soyad / Sınıf / Kitapçık)
 *      için her bubble'da en koyu vs ikinci en koyu karşılaştırması yap.
 */
public class OmrProcessor {

    /** Tüm OMR loglarını tek bir tag altında topluyoruz: logcat'te "OMR" filtresiyle bulun. */
    private static final String TAG = "OMR";

    /**
     * Kağıt içi parlaklık yayılımı eşiği; üzerinde okuma iptali / canlı geri sayım durur.
     * Hafif gölgelere tolerans için orta-yüksek tutulur (çok düşük değer sürekli yanlış pozitif üretirdi).
     */
    private static final float SHADOW_SPREAD_THRESHOLD = 0.158f;
    private static final int SHADOW_GRID = 4;
    /** Köşe marker ve kenar vignette için iç boşluk (kağıt genişliğinin yüzdesi). */
    private static final float SHADOW_INSET_FRAC = 0.09f;

    /**
     * {@link #process(Bitmap, List, int, int, int)} sonucu: gölge nedeniyle iptal bilgisi için.
     */
    public static final class ProcessResult {
        public final Map<Long, List<String>> answers;
        /** true ise düzensiz aydınlatma / gölge — okuma güvenilir sayılmadı, {@link #answers} boş olabilir. */
        public final boolean unevenIllumination;
        /** 0..~1: kağıt içi grid/kadran parlaklık yayılımı (debug). */
        public final float illuminationSpread;

        ProcessResult(Map<Long, List<String>> answers, boolean unevenIllumination, float illuminationSpread) {
            this.answers = answers;
            this.unevenIllumination = unevenIllumination;
            this.illuminationSpread = illuminationSpread;
        }
    }

    // ─── Marker tespiti ─────────────────────────────────────────────────────
    /**
     * Köşe aramasında kullanılan sabit üst sınır (adaptif eşik bunun altında kalır).
     * Gölgede kağıt ortalaması düştükçe yerel eşik {@link #findMarkerCenter} içinde düşer.
     */
    private static final int MARKER_DARK_THRESHOLD = 118;
    /** Bir köşe arama bölgesinde olması gereken minimum koyu piksel (düşük çözünürlük önizleme için 11). */
    private static final int MARKER_MIN_DARK_PIXELS = 11;
    /** Bu sayıdan fazla koyu piksel varsa bunu marker SAYMAZ (kot/parmak/gölge).
     *  Yüksek çözünürlüklü kameralar için 80K. */
    private static final int MARKER_MAX_DARK_PIXELS = 80_000;
    private static final int[] MARKER_HIST_SLACK = {0, 4, 8};
    private static final float[] MARKER_LOCAL_FRACS = {0.24f, 0.32f, 0.18f, 0.40f, 0.28f};

    // ─── Kağıt tespiti ──────────────────────────────────────────────────────
    /** Bir piksel "parlak" sayılması için min. parlaklık. Loş fotoğraflar için 155. */
    private static final int PAPER_BRIGHT_THRESHOLD = 155;

    // ─── Bubble seçimi (MCQ = 2–6 şık, LONG = 7+ şık / Ad Soyad) ─────────────
    /**
     * Örnekleme: bubble'ın büyük kısmının ortalama parlaklığı (harf mürekkebi
     * yutulur). MCQ ve LONG için ayrı eşikler: ABC'de soluk kurşun küçük gap
     * verir; 29 harfte harf baskısı bazen tek bir harfi çok koyu gösterir —
     * LONG'ta gap ve mutlak karanlık biraz daha sıkı, median yedeği açık.
     */
    /** MCQ: ikinci şıktan min. parlaklık farkı (Türkçe ABC loglarında 0.04–0.12). */
    private static final float MCQ_MIN_GAP = 0.065f;
    /** MCQ: işaret bu parlaklığın altında olmalı (birincil kural). */
    private static final float MCQ_ABS_MAX = 0.83f;
    /** MCQ: median yedeği — satırdan ne kadar daha koyu. */
    private static final float MCQ_MEDIAN_GAP = 0.048f;
    private static final float MCQ_MEDIAN_MAX_BRIGHT = 0.86f;

    /** LONG birincil: net şık ayrımı + mutlak karanlık. */
    private static final float LONG_MIN_GAP = 0.15f;
    private static final float LONG_ABS_MAX = 0.72f;
    /** LONG: median yedeği — baskı gürültüsünde tek harf hafif koyu kalmasın (E/H yanlışları). */
    private static final float LONG_MEDIAN_GAP = 0.13f;
    /** LONG median: en koyu şık bundan parlak olmasın (dolu kurşun tipik &lt; 0.72). */
    private static final float LONG_MEDIAN_MAX_BRIGHT = 0.735f;
    /** LONG median ek güvenlik: 1. ile 2. şık arasında en az bu kadar fark olmalı. */
    private static final float LONG_MEDIAN_MIN_PAIR_GAP = 0.082f;
    /**
     * LONG "yumuşak" kurtarma: soluk kurşunda gap 0.06–0.14 arasında kalır ama satır medyanına
     * göre belirgin koyulaşma vardır (U sütunu logları). Birincil/median ikisi de düşerken
     * stabil okuma sağlar; düz satırda (spread küçük) tetiklenmez.
     */
    private static final float LONG_SOFT_PAIR_GAP = 0.055f;
    private static final float LONG_SOFT_MED_DELTA = 0.098f;
    private static final float LONG_SOFT_MAX_BRIGHT = 0.77f;
    private static final float LONG_SOFT_MIN_SPREAD = 0.052f;
    /**
     * "Çift-aday" belirsizliği: en koyu iki şık birbirine bu kadar yakın AND her ikisi de
     * medianın altında belirgin oranda kalırsa (ikisi de işaret gibi görünüyor) — net karar
     * verilemez, boş bırak. Yanlış harf okumaktansa boş bırakmak daha güvenli.
     */
    private static final float LONG_DOUBLE_MARK_GAP = 0.040f;
    private static final float LONG_DOUBLE_MARK_MED_DELTA = 0.06f;

    /** MCQ: tüm şıklar birbirine çok yakınsa (homografi kayması / örnek hatası) işaret yok say. */
    private static final float MCQ_MIN_ROW_SPREAD = 0.038f;

    /**
     * @deprecated Yerine {@link #processWithDiagnostics(Bitmap, List, int, int, int)} kullanın;
     *     gölge iptali için {@link ProcessResult} gerekir.
     */
    @Deprecated
    public static Map<Long, List<String>> process(Bitmap bitmap, List<OptikFormAlan> alanlar,
                                                  int pdfWidthPt, int pdfHeightPt,
                                                  int canvasWidthDp) {
        return processWithDiagnostics(bitmap, alanlar, pdfWidthPt, pdfHeightPt, canvasWidthDp).answers;
    }

    public static ProcessResult processWithDiagnostics(Bitmap bitmap, List<OptikFormAlan> alanlar,
                                                       int pdfWidthPt, int pdfHeightPt,
                                                       int canvasWidthDp) {
        Map<Long, List<String>> result = new HashMap<>();
        if (bitmap == null || alanlar == null || alanlar.isEmpty()) {
            Log.w(TAG, "process: bitmap=" + (bitmap == null ? "null" : "ok")
                + " alanlar=" + (alanlar == null ? "null" : "size=" + alanlar.size()));
            return new ProcessResult(result, false, 0f);
        }

        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();
        Log.i(TAG, "═══════ OMR.process BAŞLADI ═══════");
        Log.i(TAG, "Bitmap: " + imgW + "x" + imgH
            + " | PDF beklenen: " + pdfWidthPt + "x" + pdfHeightPt + "pt"
            + " | canvasWidth: " + canvasWidthDp + "dp"
            + " | alan sayısı: " + alanlar.size());

        int[] pixels = new int[imgW * imgH];
        bitmap.getPixels(pixels, 0, imgW, 0, 0, imgW, imgH);

        // ── Adım 1: kağıdın bounding box'ını bul ────────────────────────────
        int[] paper = detectPaperBounds(pixels, imgW, imgH);
        int pl = paper[0], ptop = paper[1], pr = paper[2], pb = paper[3];
        int paperW = pr - pl;
        int paperH = pb - ptop;
        boolean paperFallback = false;
        if (paperW < imgW / 4 || paperH < imgH / 4) {
            // Kağıt tespiti güvenilmez → tüm görüntüyü kullan
            pl = 0; ptop = 0; pr = imgW; pb = imgH;
            paperW = imgW; paperH = imgH;
            paperFallback = true;
        } else {
            // Üst/alt kenar bazen tüm satırı "parlak" sanıp y=0 veya y=max'a yapışır;
            // köşe marker araması görüntü dışı / sandalye pikseli içerir → homografi uçar.
            int insetY = Math.max(6, imgH / 40);
            int insetX = Math.max(6, imgW / 40);
            if (paperH > imgH * 0.72f && ptop < insetY) {
                ptop = insetY;
                paperH = pb - ptop;
                Log.w(TAG, "[1/6] Üst kenar inset: kağıt üstü görüntüye yapışmış olabilir, +" + insetY + " px");
            }
            if (paperH > imgH * 0.72f && pb > imgH - insetY) {
                pb = imgH - insetY;
                paperH = pb - ptop;
                Log.w(TAG, "[1/6] Alt kenar inset: alt kenar yapışmış olabilir, -" + insetY + " px");
            }
            if (paperW > imgW * 0.72f && pl < insetX) {
                pl = insetX;
                paperW = pr - pl;
            }
            if (paperW > imgW * 0.72f && pr > imgW - insetX) {
                pr = imgW - insetX;
                paperW = pr - pl;
            }
            if (pr <= pl + 50 || pb <= ptop + 50) {
                pl = paper[0]; ptop = paper[1]; pr = paper[2]; pb = paper[3];
                paperW = pr - pl;
                paperH = pb - ptop;
                Log.w(TAG, "[1/6] Inset kağıdı çok küçülttü — orijinal sınıra dönüldü");
            }
        }
        Log.i(TAG, "[1/6] Kağıt sınırı: x=" + pl + ".." + pr + " (w=" + paperW + ")"
            + ", y=" + ptop + ".." + pb + " (h=" + paperH + ")"
            + (paperFallback ? "  ← ZAYIF tespit, tüm görüntü kullanılıyor"
                              : "  ← güvenilir"));

        float illuminationSpread = 0f;
        // Tek renkli beyaz nokta ölçümü yerel gölgeleri görmez; kağıt içi parlaklık yayılımını ölç.
        if (!paperFallback) {
            illuminationSpread = computePaperIlluminationSpread(pixels, imgW, imgH,
                pl, ptop, pr, pb);
            Log.i(TAG, "[1.5/6] Aydınlatma düzgünlüğü: spread="
                + String.format(Locale.US, "%.3f", illuminationSpread)
                + " (eşik " + String.format(Locale.US, "%.3f", SHADOW_SPREAD_THRESHOLD)
                + " üzeri → güçlü gölge / tek yönlü ışık)");
            if (illuminationSpread >= SHADOW_SPREAD_THRESHOLD) {
                Log.w(TAG, "  → Okuma iptal: kağıt üzerinde belirgin gölge veya çok düzensiz ışık.");
                return new ProcessResult(new HashMap<Long, List<String>>(), true, illuminationSpread);
            }
        }

        // ── Adım 2: aydınlatma için "white point" referansı ─────────────────
        // Loş fotoğrafta kağıt ~0.75-0.85 olabilir, 1.0 değil. Bu referansa
        // bölerek mutlak eşiklerin pozlamadan bağımsız çalışmasını sağlarız.
        float whitePoint = estimateWhitePoint(pixels, imgW, imgH, pl, ptop, pr, pb);
        Log.i(TAG, "[2/6] White-point: " + String.format(Locale.US, "%.3f", whitePoint)
            + " (1.0=ideal beyaz, <0.7=fotoğraf çok karanlık)");

        // ── Adım 3: marker'ları SADECE kağıt içinde ara ─────────────────────
        // Köşe penceresi canlı önizleme ile aynı (geniş + kenar payı + eksik köşe tamamlama).
        int expectedMarkerPx = Math.max(8, Math.round(paperW * (PdfGenerator.MARKER_PT / (float) pdfWidthPt)));
        PointF[] cm = findFourCornerMarkersInPaperRect(pixels, imgW, imgH,
            pl, ptop, pr, pb, paperW, expectedMarkerPx, pdfWidthPt, pdfHeightPt);
        PointF tlImg = cm[0], trImg = cm[1], blImg = cm[2], brImg = cm[3];
        int found = (tlImg != null ? 1 : 0) + (trImg != null ? 1 : 0)
                  + (blImg != null ? 1 : 0) + (brImg != null ? 1 : 0);
        Log.i(TAG, "[3/6] Köşe arama (geniş pencere + tamamlama) | bulunan: " + found + "/4");
        Log.i(TAG, "      TL=" + fmtPt(tlImg) + "  TR=" + fmtPt(trImg)
            + "  BL=" + fmtPt(blImg) + "  BR=" + fmtPt(brImg));
        // Beklenen marker konumları (kağıt sınırına göre)
        float mcExpPt = PdfGenerator.getMarkerCenterOffsetPt(pdfWidthPt, pdfHeightPt);
        float mcExpPx = mcExpPt * paperW / (float) pdfWidthPt;
        Log.i(TAG, "      Beklenen: TL≈(" + Math.round(pl + mcExpPx) + "," + Math.round(ptop + mcExpPx) + ")"
            + " TR≈(" + Math.round(pr - mcExpPx) + "," + Math.round(ptop + mcExpPx) + ")"
            + " BL≈(" + Math.round(pl + mcExpPx) + "," + Math.round(pb - mcExpPx) + ")"
            + " BR≈(" + Math.round(pr - mcExpPx) + "," + Math.round(pb - mcExpPx) + ")");

        // ── Adım 4: homografi kur (eğer 4 köşe makul dikdörtgen oluşturuyorsa) ─
        float mc = PdfGenerator.getMarkerCenterOffsetPt(pdfWidthPt, pdfHeightPt);
        float[] pdfCorners = {
            mc,                       mc,
            pdfWidthPt - mc,          mc,
            mc,                       pdfHeightPt - mc,
            pdfWidthPt - mc,          pdfHeightPt - mc
        };
        Matrix homography = new Matrix();
        boolean perspectiveOk = false;
        boolean quadOk = false;
        if (tlImg != null && trImg != null && blImg != null && brImg != null) {
            float[] imgCorners = {
                tlImg.x, tlImg.y,
                trImg.x, trImg.y,
                blImg.x, blImg.y,
                brImg.x, brImg.y
            };
            quadOk = isReasonableQuad(imgCorners, paperW, paperH);
            if (quadOk) {
                perspectiveOk = homography.setPolyToPoly(pdfCorners, 0, imgCorners, 0, 4);
            }
        }
        Log.i(TAG, "[4/6] Homografi: 4köşe=" + (found == 4 ? "EVET" : "HAYIR(" + found + ")")
            + " | makulDikdörtgen=" + (quadOk ? "EVET" : "HAYIR")
            + " | perspektifOK=" + perspectiveOk
            + (perspectiveOk ? "" : "  ← LİNEER FALLBACK kullanılıyor (KAYBOLAN DOĞRULUK)"));

        // ── Adım 5: piksel/pt ölçeği ve örnekleme yarıçapı ──────────────────
        float pdfSpanH = pdfWidthPt - 2f * mc;
        float ptToPx = (float) paperW / pdfWidthPt;
        if (perspectiveOk && tlImg != null && trImg != null && pdfSpanH > 0f) {
            float dx = trImg.x - tlImg.x, dy = trImg.y - tlImg.y;
            float pxSpan = (float) Math.sqrt(dx * dx + dy * dy);
            if (pxSpan > 0f) ptToPx = pxSpan / pdfSpanH;
        }
        // Bubble yarıçapı PdfGenerator'da cs * 0.38 ≈ 10.64pt; merkezler arası 28pt.
        // sample = 0.27 (bubble içi ~yarısı) → harf "yutulur", dolu mark dominant olur.
        //   Daha küçük tutmak dolu işaretin parlaklığa katkısını artırır (boş 0.90 vs dolu 0.40 ayrımı korunur).
        // search = 0.16 → ~7px @ ptToPx=1.5: küçük homografi/marker kaymalarını telafi eder.
        //   Bubble merkezleri 28pt × ptToPx ≈ 42px aralıklı; 7px arama komşu bubble'a girmez.
        int sampleRadius = Math.max(4, Math.round(28f * ptToPx * 0.27f));
        int searchRadius = Math.max(2, Math.round(28f * ptToPx * 0.16f));
        Log.i(TAG, "[5/6] ptToPx=" + String.format(Locale.US, "%.3f", ptToPx)
            + " | sampleR=" + sampleRadius + "px"
            + " | searchR=" + searchRadius + "px"
            + " (bubble yarıçapı ≈ " + Math.round(10.64f * ptToPx) + "px)");

        float pdfScale = (float) pdfWidthPt / canvasWidthDp;
        final int finalPl = pl, finalPtop = ptop;
        final int finalPaperW = paperW, finalPaperH = paperH;
        final boolean finalPersp = perspectiveOk;

        // ── Adım 6: tüm alanlar (cevap + bilgi) için bubble okuma ───────────
        Log.i(TAG, "[6/6] Eşikler — MCQ(≤6 şık): gap≥" + MCQ_MIN_GAP + " min<" + MCQ_ABS_MAX
            + " | medYedek: Δmed≥" + MCQ_MEDIAN_GAP + " min<" + MCQ_MEDIAN_MAX_BRIGHT
            + " | satırSpread≥" + MCQ_MIN_ROW_SPREAD
            + " || LONG(7+): gap≥" + LONG_MIN_GAP + " min<" + LONG_ABS_MAX
            + " | medYedek: Δmed≥" + LONG_MEDIAN_GAP + " min<" + LONG_MEDIAN_MAX_BRIGHT
            + " gap≥" + LONG_MEDIAN_MIN_PAIR_GAP
            + " | soft: gap∈[" + LONG_SOFT_PAIR_GAP + ".." + LONG_MIN_GAP + ") Δmed≥" + LONG_SOFT_MED_DELTA
            + " spr≥" + LONG_SOFT_MIN_SPREAD
            + " | 2x✗: gap<" + LONG_DOUBLE_MARK_GAP + " & Δmed(2.)≥" + LONG_DOUBLE_MARK_MED_DELTA + ")");
        for (OptikFormAlan alan : alanlar) {
            String desen = alan.desen != null ? alan.desen : "ABCD";
            char[] opts = desen.toCharArray();
            int perBlock = alan.bloktakiVeriSayisi > 0 ? alan.bloktakiVeriSayisi : 5;
            int blocks   = alan.blokSayisi > 0        ? alan.blokSayisi          : 1;
            int totalQ   = perBlock * blocks;
            Log.i(TAG, "  ─ Alan #" + alan.id + " '" + alan.etiket
                + "' tur=" + alan.tur + " desen=" + desen
                + " soru=" + totalQ + "(" + blocks + "x" + perBlock + ")");

            int markedCount = 0;
            StringBuilder firstSamples = new StringBuilder();
            List<String> answers = new ArrayList<>();
            for (int q = 0; q < totalQ; q++) {
                boolean longRow = opts.length > 6;
                // Ad Soyad (29 harf): örnek biraz daha geniş + arama +2..3 px — soluk işaret ve
                // homografi titreşiminde gap artar; komşu sütun ~42 px uzakta güvenli.
                int effSampleR = sampleRadius;
                int effSearchR = searchRadius;
                if (longRow) {
                    effSampleR = Math.min(sampleRadius + 1, 15);
                    effSearchR = Math.min(searchRadius + 3, 11);
                }

                float[] brightnesses = new float[opts.length];
                // İlk 2 soru için örnekleme koordinatlarını logla (tanı amaçlı)
                boolean logCoords = (q < 2);
                StringBuilder coordLog = logCoords ? new StringBuilder() : null;
                if (logCoords) coordLog.append("  [COORD] Q").append(q + 1).append(": ");
                float fieldScale = PdfGenerator.getFieldScaleForPage(pdfWidthPt, pdfHeightPt);
                for (int o = 0; o < opts.length; o++) {
                    float[] pdfPt = PdfGenerator.getBubbleCenter(alan, q, o, pdfScale, fieldScale);
                    float ix, iy;
                    if (finalPersp) {
                        float[] pt = {pdfPt[0], pdfPt[1]};
                        homography.mapPoints(pt);
                        ix = pt[0]; iy = pt[1];
                    } else {
                        // Fallback: kağıt-sınırlı lineer ölçek
                        ix = finalPl + pdfPt[0] * finalPaperW / pdfWidthPt;
                        iy = finalPtop + pdfPt[1] * finalPaperH / pdfHeightPt;
                    }
                    if (logCoords) {
                        coordLog.append(opts[o]).append("→pdf(")
                            .append(Math.round(pdfPt[0])).append(",")
                            .append(Math.round(pdfPt[1])).append(")img(")
                            .append(Math.round(ix)).append(",")
                            .append(Math.round(iy)).append(") ");
                    }
                    // Bubble merkezi etrafında küçük bir gridde her noktada
                    // BÜYÜK bir dairesel bölgenin ORTALAMA parlaklığını al;
                    // aday noktalardan en koyu (ortalama olarak) olanı seç.
                    // Bu yaklaşım dairenin İÇİNDEKİ HARFİ "yutar" çünkü harf
                    // toplam alanın çok küçük bir oranıdır → boş daire ~0.90,
                    // dolu daire ~0.40 → 0.50'lik net ayrım.
                    float raw = sampleAverageNearby(pixels,
                        Math.round(ix), Math.round(iy),
                        effSampleR, effSearchR, imgW, imgH);
                    // White-point normalize: loş fotoğrafta tüm okumaların eşik
                    // altına kaymasını engeller.
                    brightnesses[o] = raw / whitePoint;
                }
                if (logCoords) Log.i(TAG, coordLog.toString());

                // En koyu ve İKİNCİ EN KOYU şıkları bul.
                // Bu, ortalama vs minimum'dan çok daha sağlam:
                // gri baskıda renk hep birlikte kayar, ortalama küçük gözükür.
                float minBr = Float.MAX_VALUE, secondMinBr = Float.MAX_VALUE;
                int darkestIdx = 0;
                for (int o = 0; o < opts.length; o++) {
                    if (brightnesses[o] < minBr) {
                        secondMinBr = minBr;
                        minBr = brightnesses[o];
                        darkestIdx = o;
                    } else if (brightnesses[o] < secondMinBr) {
                        secondMinBr = brightnesses[o];
                    }
                }
                if (opts.length == 1) secondMinBr = 1f; // tek-şık edge case

                float maxBr = Float.MIN_VALUE;
                for (int o = 0; o < opts.length; o++) {
                    if (brightnesses[o] > maxBr) maxBr = brightnesses[o];
                }
                float rowSpread = maxBr - minBr;

                // İşaretli kabul: MCQ (≤6 şık) ve LONG (7+, Ad Soyad) ayrı eşikler
                String selected = "";
                float gap = secondMinBr - minBr;
                float rowMedian = medianBrightness(brightnesses);

                boolean isMcq = opts.length <= 6;
                /** ABC satırında üç şık da aynı parlaklığa yakınsa = örnek kağıt dışında / homografi çöktü — seçme. */
                boolean mcqAmbiguous = isMcq && opts.length >= 2
                    && rowSpread < MCQ_MIN_ROW_SPREAD;

                float reqGap = isMcq ? MCQ_MIN_GAP : LONG_MIN_GAP;
                float reqAbs = isMcq ? MCQ_ABS_MAX : LONG_ABS_MAX;
                boolean gapOk = gap >= reqGap;
                boolean darkOk = minBr < reqAbs;
                boolean primaryOk = gapOk && darkOk && !mcqAmbiguous;

                boolean medianOk;
                if (mcqAmbiguous) {
                    medianOk = false;
                } else if (isMcq) {
                    medianOk = opts.length >= 2
                        && (rowMedian - minBr) >= MCQ_MEDIAN_GAP
                        && minBr < MCQ_MEDIAN_MAX_BRIGHT;
                } else {
                    medianOk = gap >= LONG_MEDIAN_MIN_PAIR_GAP
                        && (rowMedian - minBr) >= LONG_MEDIAN_GAP
                        && minBr < LONG_MEDIAN_MAX_BRIGHT;
                }

                boolean longSoftOk = !isMcq && !mcqAmbiguous
                    && gap >= LONG_SOFT_PAIR_GAP && gap < LONG_MIN_GAP
                    && (rowMedian - minBr) >= LONG_SOFT_MED_DELTA
                    && minBr < LONG_SOFT_MAX_BRIGHT
                    && rowSpread >= LONG_SOFT_MIN_SPREAD;

                // LONG: iki şık çok yakın ve ikisi de medianın belirgin altında kalırsa
                // (R≈U gibi durum) hangisinin gerçek mark olduğunu söyleyemeyiz → boş.
                boolean longDoubleMark = !isMcq && opts.length >= 3
                    && gap < LONG_DOUBLE_MARK_GAP
                    && (rowMedian - secondMinBr) >= LONG_DOUBLE_MARK_MED_DELTA;

                String ruleTag = "";
                if (longDoubleMark) {
                    ruleTag = " [long-2x✗]";
                } else if (primaryOk) {
                    ruleTag = isMcq ? " [mcq-1°]" : " [long-1°]";
                } else if (medianOk) {
                    ruleTag = isMcq ? " [mcq-med]" : " [long-med]";
                } else if (longSoftOk) {
                    ruleTag = " [long-soft]";
                }

                if (!longDoubleMark && (primaryOk || medianOk || longSoftOk)) {
                    selected = String.valueOf(opts[darkestIdx]);
                    markedCount++;
                }
                answers.add(selected);

                // İlk 3 sorunun detaylı logu — soru-bazında debug için
                if (q < 3) {
                    StringBuilder b = new StringBuilder();
                    b.append("    Q").append(q + 1).append(": ");
                    for (int o = 0; o < opts.length; o++) {
                        b.append(opts[o]).append('=')
                         .append(String.format(Locale.US, "%.2f", brightnesses[o]))
                         .append(' ');
                    }
                    b.append("→ darkest=").append(opts[darkestIdx])
                     .append(" gap=").append(String.format(Locale.US, "%.2f", gap))
                     .append(gapOk ? "✓" : "✗").append('/').append(String.format(Locale.US, "%.2f", reqGap))
                     .append(" min=").append(String.format(Locale.US, "%.2f", minBr))
                     .append(darkOk ? "✓" : "✗").append('/').append(String.format(Locale.US, "%.2f", reqAbs))
                     .append(" spr=").append(String.format(Locale.US, "%.2f", rowSpread))
                     .append(mcqAmbiguous ? " AMB✗" : "")
                     .append(" med=").append(String.format(Locale.US, "%.2f", rowMedian))
                     .append(medianOk ? " med✓" : " med✗")
                     .append(longSoftOk ? " soft✓" : "")
                     .append(ruleTag.isEmpty() ? "" : ruleTag)
                     .append(" → ").append(selected.isEmpty() ? "(boş)" : selected);
                    if (firstSamples.length() > 0) firstSamples.append('\n');
                    firstSamples.append(b);
                }
            }
            Log.i(TAG, "    Sonuç: " + markedCount + "/" + totalQ + " soru işaretli\n"
                + firstSamples.toString());
            result.put(alan.id, answers);
        }
        Log.i(TAG, "═══════ OMR.process BİTTİ ═══════");
        return new ProcessResult(result, false, illuminationSpread);
    }

    /** PointF'i kompakt formatta yazdır (debug için). */
    private static String fmtPt(PointF p) {
        return p == null ? "null" : "(" + Math.round(p.x) + "," + Math.round(p.y) + ")";
    }

    /** Şık parlaklıklarının median'ı [0..1]; ABC/ABCD satırında göreli karanlık için. */
    private static float medianBrightness(float[] v) {
        int n = v.length;
        if (n == 0) return 1f;
        float[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        if ((n & 1) == 1) return c[n / 2];
        return (c[n / 2 - 1] + c[n / 2]) * 0.5f;
    }

    // ════════════════════════════════════════════════════════════════════════
    //                              YARDIMCI METODLAR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Kağıt içi (köşe markerları hariç tutulmuş) parlaklık homojenliği ölçümü.
     * Ortalama Luma [0..1] üzerinden max−min: güçlü gölge veya tek yönlü ışık yüksek spread verir.
     */
    private static float computePaperIlluminationSpread(int[] pixels, int imgW, int imgH,
                                                        int pl, int ptop, int pr, int pb) {
        int pw = pr - pl, ph = pb - ptop;
        int insetX = Math.max(12, Math.round(pw * SHADOW_INSET_FRAC));
        int insetY = Math.max(12, Math.round(ph * SHADOW_INSET_FRAC));
        int il = pl + insetX, it = ptop + insetY, ir = pr - insetX, ib = pb - insetY;
        if (ir <= il + 30 || ib <= it + 30) return 0f;

        float gridSpread = gridMeanLumaSpread(pixels, imgW, imgH, il, it, ir, ib,
            SHADOW_GRID, SHADOW_GRID);
        float quadSpread = quadrantMeanLumaSpread(pixels, imgW, imgH, il, it, ir, ib);
        return Math.max(gridSpread, quadSpread);
    }

    /** Düzgün ızgarada her hücrenin ortalama Luma'sı; en parlak vs en koyu hücre farkı. */
    private static float gridMeanLumaSpread(int[] pixels, int imgW, int imgH,
            int il, int it, int ir, int ib, int cols, int rows) {
        float minM = 1f, maxM = 0f;
        int innerW = ir - il, innerH = ib - it;
        int cellW = innerW / cols;
        int cellH = innerH / rows;
        if (cellW < 4 || cellH < 4) return 0f;

        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int x0 = il + cx * cellW;
                int y0 = it + cy * cellH;
                int x1 = (cx == cols - 1) ? ir : il + (cx + 1) * cellW;
                int y1 = (cy == rows - 1) ? ib : it + (cy + 1) * cellH;
                float m = meanNormalizedLuma(pixels, imgW, imgH, x0, y0, x1, y1);
                if (m < minM) minM = m;
                if (m > maxM) maxM = m;
            }
        }
        return maxM - minM;
    }

    /** Dört büyük kadranın (2×2) ortalama parlaklık yayılımı — tek köşe gölgelerinde güçlü sinyal. */
    private static float quadrantMeanLumaSpread(int[] pixels, int imgW, int imgH,
            int il, int it, int ir, int ib) {
        int midX = (il + ir) / 2;
        int midY = (it + ib) / 2;
        float q00 = meanNormalizedLuma(pixels, imgW, imgH, il, it, midX, midY);
        float q10 = meanNormalizedLuma(pixels, imgW, imgH, midX, it, ir, midY);
        float q01 = meanNormalizedLuma(pixels, imgW, imgH, il, midY, midX, ib);
        float q11 = meanNormalizedLuma(pixels, imgW, imgH, midX, midY, ir, ib);
        float minQ = Math.min(Math.min(q00, q10), Math.min(q01, q11));
        float maxQ = Math.max(Math.max(q00, q10), Math.max(q01, q11));
        return maxQ - minQ;
    }

    // Minimum luma to be counted as paper background (not printed content)
    private static final int SHADOW_BG_MIN_LUMA = 90;

    private static float meanNormalizedLuma(int[] pixels, int imgW, int imgH,
            int x0, int y0, int x1, int y1) {
        int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 14);
        long bgSum = 0;
        int bgCnt = 0;
        int totalCnt = 0;
        for (int y = y0; y < y1 && y < imgH; y += step) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x += step) {
                int p = pixels[rowBase + x];
                int lum = ((p >> 16 & 0xFF) * 299
                         + (p >>  8 & 0xFF) * 587
                         + (p       & 0xFF) * 114) / 1000;
                totalCnt++;
                if (lum >= SHADOW_BG_MIN_LUMA) {
                    bgSum += lum;
                    bgCnt++;
                }
            }
        }
        // If fewer than 10% of pixels qualify as background, the cell is
        // dominated by printed content — treat it as neutral rather than "dark".
        if (bgCnt < Math.max(1, totalCnt / 10)) return 0.75f;
        return (bgSum / (float) bgCnt) / 255f;
    }

    /**
     * Kağıt bölgesinin "white point" referansını tahmin eder — en parlak %5
     * piksellerin ortalama parlaklığı. Loş/gölgeli fotoğraflarda kağıt
     * 0.75-0.85 arası görünür; bu referansa bölmek mutlak eşiklerin pozlamadan
     * bağımsız çalışmasını sağlar.
     */
    private static float estimateWhitePoint(int[] pixels, int imgW, int imgH,
                                             int pl, int ptop, int pr, int pb) {
        int step = Math.max(1, Math.min(pr - pl, pb - ptop) / 150);
        int[] hist = new int[256];
        int total = 0;
        for (int y = ptop; y < pb && y < imgH; y += step) {
            int rowBase = y * imgW;
            for (int x = pl; x < pr && x < imgW; x += step) {
                int p = pixels[rowBase + x];
                int lum = ((p >> 16 & 0xFF) * 299
                         + (p >>  8 & 0xFF) * 587
                         + (p       & 0xFF) * 114) / 1000;
                hist[lum]++;
                total++;
            }
        }
        if (total == 0) return 1f;
        // Üst %5'lik parlaklık dilimini bul → "white" referansı
        int target = Math.max(1, total / 20);
        int cum = 0;
        for (int i = 255; i >= 0; i--) {
            cum += hist[i];
            if (cum >= target) {
                // Aşırı amplifikasyonu engelle: kağıt en az %50 parlak olmalı
                return Math.max(0.50f, i / 255f);
            }
        }
        return 1f;
    }

    /**
     * Kağıdın bounding box'ını bulur — parlak satır/sütun histogramları kullanır.
     * Koyu zemin (kot, masa) etrafında olsa bile kağıt izole edilir.
     */
    private static int[] detectPaperBounds(int[] pixels, int imgW, int imgH) {
        int[] rowBright = new int[imgH];
        int[] colBright = new int[imgW];

        // Her satır ve sütun için tam tarama — seyrek örneklemede (x/y += step) birçok
        // indeks hiç güncellenmez; sağ/sol üst/alt sınır yanlış iner, özellikle sağ üst
        // köşe marker araması boş kalır (canlı önizlemede sürekli 3/4 köşe).
        for (int y = 0; y < imgH; y++) {
            int rowBase = y * imgW;
            int rc = 0;
            for (int x = 0; x < imgW; x++) {
                int p = pixels[rowBase + x];
                int br = ((p >> 16 & 0xFF) * 299
                        + (p >>  8 & 0xFF) * 587
                        + (p       & 0xFF) * 114) / 1000;
                if (br > PAPER_BRIGHT_THRESHOLD) {
                    colBright[x]++;
                    rc++;
                }
            }
            rowBright[y] = rc;
        }

        int maxRow = 0, maxCol = 0;
        for (int v : rowBright) if (v > maxRow) maxRow = v;
        for (int v : colBright) if (v > maxCol) maxCol = v;

        int rowThresh = Math.max(1, (int) (maxRow * 0.35f));
        int colThresh = Math.max(1, (int) (maxCol * 0.35f));

        int top = 0, bottom = imgH - 1, left = 0, right = imgW - 1;
        while (top    < imgH - 1 && rowBright[top]    < rowThresh) top++;
        while (bottom > 0        && rowBright[bottom] < rowThresh) bottom--;
        while (left   < imgW - 1 && colBright[left]   < colThresh) left++;
        while (right  > 0        && colBright[right]  < colThresh) right--;

        if (top >= bottom || left >= right) {
            return new int[]{0, 0, imgW, imgH};
        }
        // 2% genişlet — köşe markerları kağıt kenarına çok yakın
        int padX = Math.max(2, (right - left) / 50);
        int padY = Math.max(2, (bottom - top) / 50);
        return new int[]{
            Math.max(0, left - padX),
            Math.max(0, top - padY),
            Math.min(imgW, right + padX),
            Math.min(imgH, bottom + padY)
        };
    }

    /**
     * Köşe arama dikdörtgeninde örneklenmiş ortalama luma (0–255) — adaptif marker eşiği için.
     */
    private static int meanLumaInRect(int[] pixels, int imgW, int imgH,
            int x0, int y0, int x1, int y1, int step) {
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1 && y < imgH; y += step) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x += step) {
                int p = pixels[rowBase + x];
                int br = ((p >> 16 & 0xFF) * 299
                        + (p >>  8 & 0xFF) * 587
                        + (p       & 0xFF) * 114) / 1000;
                sum += br;
                n++;
            }
        }
        if (n == 0) return 150;
        return (int) (sum / n);
    }

    private static int pixelLuma(int p) {
        return ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
    }

    /** Küçük kare pencerenin ortalama luma'sı. */
    private static int meanLumaSquare(int[] pixels, int imgW, int imgH, int cx, int cy, int r) {
        int x0 = Math.max(0, cx - r);
        int y0 = Math.max(0, cy - r);
        int x1 = Math.min(imgW, cx + r + 1);
        int y1 = Math.min(imgH, cy + r + 1);
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            int rb = y * imgW;
            for (int x = x0; x < x1; x++) {
                sum += pixelLuma(pixels[rb + x]);
                n++;
            }
        }
        return n == 0 ? 255 : (int) (sum / n);
    }

    /**
     * Köşe penceresinde "en koyu kare"yi bulup lokal centroid ile marker merkezini verir.
     * Amaç: gri kenar / gölge yerine gerçekten kağıttaki siyah kareye kilitlenmek.
     */
    private static PointF findMarkerCenterByDarkestSquare(int[] pixels, int imgW, int imgH,
            int x0, int y0, int x1, int y1, int expectedMarkerPx) {
        int w = x1 - x0, h = y1 - y0;
        if (w < 12 || h < 12) return null;

        int side = Math.max(8, expectedMarkerPx);
        side = Math.min(side, Math.min(w, h) - 2);
        if (side < 8) return null;
        int r = side / 2;
        int scanStep = side >= 20 ? 2 : 1;

        int bestX = -1, bestY = -1;
        int bestMean = 256;
        for (int cy = y0 + r; cy < y1 - r; cy += scanStep) {
            for (int cx = x0 + r; cx < x1 - r; cx += scanStep) {
                int m = meanLumaSquare(pixels, imgW, imgH, cx, cy, r);
                if (m < bestMean) {
                    bestMean = m;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }
        if (bestX < 0) return null;

        int refineR = Math.max(7, Math.round(expectedMarkerPx * 0.9f));
        int rx0 = Math.max(x0, bestX - refineR);
        int ry0 = Math.max(y0, bestY - refineR);
        int rx1 = Math.min(x1, bestX + refineR + 1);
        int ry1 = Math.min(y1, bestY + refineR + 1);
        if (rx1 <= rx0 || ry1 <= ry0) return null;

        int localMean = meanLumaInRect(pixels, imgW, imgH, rx0, ry0, rx1, ry1, 1);
        int T = Math.max(28, Math.min(95, localMean - 18));

        long sx = 0, sy = 0, wsum = 0;
        int cnt = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int y = ry0; y < ry1; y++) {
            int rb = y * imgW;
            for (int x = rx0; x < rx1; x++) {
                int lum = pixelLuma(pixels[rb + x]);
                if (lum <= T) {
                    int ww = 256 - lum;
                    sx += (long) x * ww;
                    sy += (long) y * ww;
                    wsum += ww;
                    cnt++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (cnt < MARKER_MIN_DARK_PIXELS || wsum <= 0) return null;

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        float ar = Math.max(bw, bh) / (float) Math.max(1, Math.min(bw, bh));
        if (ar > 2.0f) return null;

        int em = Math.max(8, expectedMarkerPx);
        int sideMax = Math.max(bw, bh);
        if (sideMax < em * 0.35f || sideMax > em * 3.0f) return null;

        return new PointF(sx / (float) wsum, sy / (float) wsum);
    }

    /**
     * Beklenen marker merkezi etrafında lokal arama.
     * Kağıt içindeki diğer koyu yazı/işaretlerin köşe sanılmasını engeller.
     */
    private static PointF findMarkerNearExpectedCenter(int[] pixels, int imgW, int imgH,
            int expCx, int expCy, int searchR, int expectedMarkerPx) {
        int x0 = Math.max(0, expCx - searchR);
        int y0 = Math.max(0, expCy - searchR);
        int x1 = Math.min(imgW, expCx + searchR + 1);
        int y1 = Math.min(imgH, expCy + searchR + 1);
        if (x1 - x0 < 20 || y1 - y0 < 20) return null;
        PointF p = findMarkerCenterByDarkestSquare(
                pixels, imgW, imgH, x0, y0, x1, y1, Math.max(8, expectedMarkerPx));
        if (p != null) return p;
        p = findMarkerCenter(
                pixels, imgW, imgH, x0, y0, x1, y1, Math.max(8, expectedMarkerPx));
        if (p != null) return p;

        // 2. deneme: pencereyi kontrollü genişlet (hala sadece beklenen köşe civarı).
        int expandedR = Math.max(searchR + expectedMarkerPx, (searchR * 3) / 2);
        int ex0 = Math.max(0, expCx - expandedR);
        int ey0 = Math.max(0, expCy - expandedR);
        int ex1 = Math.min(imgW, expCx + expandedR + 1);
        int ey1 = Math.min(imgH, expCy + expandedR + 1);
        if (ex1 - ex0 < 20 || ey1 - ey0 < 20) return null;

        p = findMarkerCenterByDarkestSquare(
                pixels, imgW, imgH, ex0, ey0, ex1, ey1, Math.max(8, expectedMarkerPx));
        if (p != null) return p;
        return findMarkerCenter(
                pixels, imgW, imgH, ex0, ey0, ex1, ey1, Math.max(8, expectedMarkerPx));
    }

    /** Marker bbox beklenen boyuta yakın mı (gölge yedeği için gevşek sınırlar). */
    private static boolean isReasonableMarkerBlob(int blobW, int blobH,
            int expectedMarkerPx, boolean useSizeCheck) {
        if (blobW < 2 || blobH < 2) return false;
        if (!useSizeCheck) {
            float ar = Math.max(blobW, blobH) / (float) Math.min(blobW, blobH);
            return ar <= 3.4f;
        }
        int em = Math.max(6, expectedMarkerPx);
        int side = Math.max(blobW, blobH);
        if (side < em * 0.26f) return false;
        if (side > em * 3.7f) return false;
        float ar = Math.max(blobW, blobH) / (float) Math.min(blobW, blobH);
        return ar <= 3.3f;
    }

    /**
     * br &lt;= T olan piksellerin ağırlık merkezi; sayım ve bbox kontrolü geçerse döner.
     */
    private static PointF centroidBelowThreshold(int[] pixels, int imgW, int imgH,
            int x0, int y0, int x1, int y1, int T,
            int expectedMarkerPx, boolean useSizeCheck) {
        long sumX = 0, sumY = 0;
        int count = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int y = y0; y < y1 && y < imgH; y++) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x++) {
                int br = pixelLuma(pixels[rowBase + x]);
                if (br <= T) {
                    sumX += x;
                    sumY += y;
                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (count < MARKER_MIN_DARK_PIXELS || count > MARKER_MAX_DARK_PIXELS) return null;
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        if (!isReasonableMarkerBlob(bw, bh, expectedMarkerPx, useSizeCheck)) return null;
        return new PointF(sumX / (float) count, sumY / (float) count);
    }

    /**
     * Gölgede tüm köşe kutusu mutlak eşikle "tamamı koyu" veya "hiç koyu yok" olunca:
     * histogramdan en koyu N piksel kütle eşiği + yerel min–max kontrast yedeği.
     */
    private static PointF findMarkerShadowFallback(int[] pixels, int imgW, int imgH,
            int x0, int y0, int x1, int y1, int expectedMarkerPx, boolean useSizeCheck) {
        int em = Math.max(8, expectedMarkerPx);
        int[] hist = new int[256];
        for (int y = y0; y < y1 && y < imgH; y++) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x++) {
                hist[pixelLuma(pixels[rowBase + x])]++;
            }
        }
        int total = 0;
        for (int v : hist) total += v;
        if (total < 80) return null;

        int[] targets = {
            Math.max(22, (em * em) / 5),
            Math.max(28, (em * em) / 3),
            Math.max(32, (em * em) / 2),
            Math.min(MARKER_MAX_DARK_PIXELS - 50, em * em),
            Math.min(MARKER_MAX_DARK_PIXELS - 50, (em * em * 5) / 4)
        };
        for (int target : targets) {
            if (target < MARKER_MIN_DARK_PIXELS || target >= total) continue;
            int cum = 0;
            int threshLum = 255;
            for (int l = 0; l < 256; l++) {
                cum += hist[l];
                if (cum >= target) {
                    threshLum = l;
                    break;
                }
            }
            for (int slack : MARKER_HIST_SLACK) {
                PointF c = centroidBelowThreshold(pixels, imgW, imgH, x0, y0, x1, y1,
                    threshLum + slack, expectedMarkerPx, useSizeCheck);
                if (c != null) return c;
            }
        }

        int minL = 255, maxL = 0;
        for (int y = y0; y < y1 && y < imgH; y++) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x++) {
                int br = pixelLuma(pixels[rowBase + x]);
                if (br < minL) minL = br;
                if (br > maxL) maxL = br;
            }
        }
        int spread = maxL - minL;
        if (spread < 12) return null;
        for (float frac : MARKER_LOCAL_FRACS) {
            int T = minL + Math.round(spread * frac);
            T = Math.min(T, maxL - 7);
            PointF c = centroidBelowThreshold(pixels, imgW, imgH, x0, y0, x1, y1,
                T, expectedMarkerPx, useSizeCheck);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * Verilen dikdörtgendeki koyu piksel ağırlık merkezini döndürür.
     *
     * İki-aşamalı yaklaşım (kritik düzeltme):
     *   Pass 1 — kabaca tüm bölgenin koyu piksel ağırlık merkezini bul.
     *            Bu merkez genellikle gerçek marker'dan 30-65 px sayfa
     *            ORTASINA doğru "kayar" çünkü kenar gölgeleri, kağıt
     *            dokusu ve uzakta olan baskı (cevap kareleri vb.) hep
     *            aynı ortalamaya katkı veriyor. Sonuç: tüm grid bir hücre
     *            kadar kayıyor → bubble'lar sayı kolonu / boşluk
     *            örnekleniyor → cevaplar rastgele görünüyor.
     *
     *   Pass 2 — kaba merkezin etrafında DAR pencere; eşik köşe bölgesi
     *            ortalama luma'ya göre uyarlanır (gölge/parlama).
     *
     * Çok az/çok fazla koyu piksel varsa → null (marker güvenilir değil).
     */
    private static PointF findMarkerCenter(int[] pixels, int imgW, int imgH,
                                            int x0, int y0, int x1, int y1,
                                            int expectedMarkerPx) {
        PointF darkestSquare = findMarkerCenterByDarkestSquare(
            pixels, imgW, imgH, x0, y0, x1, y1, Math.max(8, expectedMarkerPx));
        if (darkestSquare != null) return darkestSquare;

        // Beklenen marker boyutu negatif/eski yola uyumluluk için 0 → kontrol kapalı
        boolean useSizeCheck = expectedMarkerPx > 0;

        // Köşe bölgesi ortalama parlaklığı — gölgede beyaz kağıt grileşir; sabit eşik marker'ı kaçırır.
        int regionMean = meanLumaInRect(pixels, imgW, imgH, x0, y0, x1, y1, 4);
        // pass1Thresh: sadece gerçek siyah marker pikselleri yakalansın (luma < ~90).
        // Eski değer (regionMean-32 ≈ 142) kağıt kenarı gradyanını da yakalıyordu → centroid kenara kayıyordu.
        int pass1Thresh = Math.max(60, Math.min(90, regionMean - 120));
        int strictDark = Math.max(50, Math.min(75, regionMean - 130));

        // Pass 1: bölgenin tamamında koyu piksel merkezi + bbox.
        long sumX = 0, sumY = 0, count = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int y = y0; y < y1 && y < imgH; y++) {
            int rowBase = y * imgW;
            for (int x = x0; x < x1 && x < imgW; x++) {
                int p = pixels[rowBase + x];
                int br = ((p >> 16 & 0xFF) * 299
                        + (p >>  8 & 0xFF) * 587
                        + (p       & 0xFF) * 114) / 1000;
                if (br < pass1Thresh) {
                    sumX += x; sumY += y; count++;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }

        boolean fromShadowFallback = false;
        float roughCx;
        float roughCy;
        int blobW;
        int blobH;

        if (count < MARKER_MIN_DARK_PIXELS || count > MARKER_MAX_DARK_PIXELS) {
            PointF fb = findMarkerShadowFallback(pixels, imgW, imgH, x0, y0, x1, y1,
                expectedMarkerPx, useSizeCheck);
            if (fb == null) return null;
            roughCx = fb.x;
            roughCy = fb.y;
            blobW = Math.max(4, Math.round(expectedMarkerPx * 1.05f));
            blobH = blobW;
            fromShadowFallback = true;
        } else {
            roughCx = sumX / (float) count;
            roughCy = sumY / (float) count;
            blobW = maxX - minX;
            blobH = maxY - minY;
        }

        // Boyut sanity check: gerçek marker ±%70 toleransla beklenen boyutta olur.
        // Gölge / kağıt kenarı / parmak çok daha geniş ya da çok elongated çıkar.
        if (useSizeCheck && !fromShadowFallback) {
            int maxAllowed = Math.round(expectedMarkerPx * 2.6f); // bol pay (eğik kamera için)
            int sideMin = Math.max(blobW, blobH);
            float aspectRatio = blobW > 0 && blobH > 0
                ? Math.max(blobW, blobH) / (float) Math.min(blobW, blobH)
                : 99f;
            if (sideMin > maxAllowed || aspectRatio > 2.8f) {
                // Pass 1 büyük gölgeye düştü — pass 2'de DAR bir pencerede en yoğun bölgeyi ara,
                // başaramazsa null dön (marker güvenilir bulunamadı).
                int searchR = Math.round(expectedMarkerPx * 1.2f);
                int rx0s = Math.max(x0, (int) roughCx - searchR);
                int rx1s = Math.min(x1, (int) roughCx + searchR);
                int ry0s = Math.max(y0, (int) roughCy - searchR);
                int ry1s = Math.min(y1, (int) roughCy + searchR);
                long sx = 0, sy = 0, sc = 0;
                for (int y = ry0s; y < ry1s && y < imgH; y++) {
                    int rb = y * imgW;
                    for (int x = rx0s; x < rx1s && x < imgW; x++) {
                        int p = pixels[rb + x];
                        int br = ((p >> 16 & 0xFF) * 299
                                + (p >>  8 & 0xFF) * 587
                                + (p       & 0xFF) * 114) / 1000;
                        if (br < strictDark) {
                            sx += x; sy += y; sc++;
                        }
                    }
                }
                if (sc < MARKER_MIN_DARK_PIXELS) return null;
                roughCx = sx / (float) sc;
                roughCy = sy / (float) sc;
            }
        }

        // Pass 2: kaba merkezin etrafında dar pencere + sıkı eşik.
        // Beklenen boyut biliniyorsa pencereyi marker boyutunun ~1.4 katına sıkıştır
        // — uzak gölge bias'ı tamamen elenir.
        int refineR = useSizeCheck
            ? Math.round(expectedMarkerPx * 1.4f)
            : Math.max(20, Math.min(x1 - x0, y1 - y0) / 4);
        int rx0 = Math.max(x0, (int) roughCx - refineR);
        int rx1 = Math.min(x1, (int) roughCx + refineR);
        int ry0 = Math.max(y0, (int) roughCy - refineR);
        int ry1 = Math.min(y1, (int) roughCy + refineR);

        int strictPass2 = strictDark;
        if (fromShadowFallback) {
            int lx0 = Math.max(x0, (int) roughCx - 26);
            int ly0 = Math.max(y0, (int) roughCy - 26);
            int lx1 = Math.min(x1, (int) roughCx + 26);
            int ly1 = Math.min(y1, (int) roughCy + 26);
            int rm = meanLumaInRect(pixels, imgW, imgH, lx0, ly0, lx1, ly1, 2);
            strictPass2 = Math.max(50, Math.min(105, rm - 17));
        }

        long sumX2 = 0, sumY2 = 0, count2 = 0;
        for (int y = ry0; y < ry1 && y < imgH; y++) {
            int rowBase = y * imgW;
            for (int x = rx0; x < rx1 && x < imgW; x++) {
                int p = pixels[rowBase + x];
                int br = ((p >> 16 & 0xFF) * 299
                        + (p >>  8 & 0xFF) * 587
                        + (p       & 0xFF) * 114) / 1000;
                if (br < strictPass2) {
                    sumX2 += x; sumY2 += y; count2++;
                }
            }
        }
        if (count2 < MARKER_MIN_DARK_PIXELS) {
            // Pass 2 yeterli koyu piksel bulamadı → kaba merkezi kullan
            return new PointF(roughCx, roughCy);
        }
        return new PointF(sumX2 / (float) count2, sumY2 / (float) count2);
    }

    /**
     * Dört köşenin "makul dikdörtgen" oluşturduğunu doğrular.
     * Sıra: TL, TR, BL, BR (her biri x,y → toplam 8 float).
     */
    private static boolean isReasonableQuad(float[] c, int paperW, int paperH) {
        float tlx = c[0], tly = c[1];
        float trx = c[2], try_ = c[3];
        float blx = c[4], bly = c[5];
        float brx = c[6], bry = c[7];

        // TL sol-üst, TR sağ-üst, BL sol-alt, BR sağ-alt olmalı
        if (trx <= tlx + 10 || brx <= blx + 10) return false;
        if (bly <= tly + 10 || bry <= try_ + 10) return false;

        // Quadrilateral kenarları kağıdın en az %40'ı kadar uzun olmalı
        float topW = trx - tlx;
        float botW = brx - blx;
        float lefH = bly - tly;
        float rigH = bry - try_;
        if (topW < paperW * 0.4f || botW < paperW * 0.4f) return false;
        if (lefH < paperH * 0.4f || rigH < paperH * 0.4f) return false;
        return true;
    }

    /** Dairesel bir alanın normalize ortalama parlaklığı [0=siyah, 1=beyaz]. */
    private static float sampleBrightness(int[] pixels, int cx, int cy, int radius,
                                           int imgW, int imgH) {
        long sum = 0;
        int count = 0;
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > r2) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || x >= imgW || y < 0 || y >= imgH) continue;
                int p = pixels[y * imgW + x];
                sum += ((p >> 16 & 0xFF) * 299
                      + (p >>  8 & 0xFF) * 587
                      + (p       & 0xFF) * 114) / 1000;
                count++;
            }
        }
        return count == 0 ? 1f : sum / (255f * count);
    }

    /**
     * (cx, cy) merkezi etrafında ±searchRadius bölgesinde KAYDIRMALI ARAMA yapar.
     * Her aday merkezde {@code sampleRadius}'lık BÜYÜK dairesel bir bölgenin
     * ORTALAMA parlaklığını hesaplar; bunlardan EN KOYU OLANI döndürür.
     *
     * Önemli fark: sampleRadius bilinçli olarak BÜYÜK (bubble'ın %75-80'i).
     * Böylece dairenin ortasındaki HARF (A/B/C/D vs.) sample alanının çok küçük
     * bir oranını kaplar ve ortalamayı belirgin şekilde etkilemez:
     *  - Boş daire (orta harf): ortalama ~0.90 (beyaz dominant)
     *  - Dolu daire: ortalama ~0.40
     * searchRadius küçük (~2 piksel) → koordinat haritalamasında bir-iki
     * piksellik kaymayı tolere etmek için.
     */
    private static float sampleAverageNearby(int[] pixels, int cx, int cy,
                                              int sampleRadius, int searchRadius,
                                              int imgW, int imgH) {
        // searchR büyüdüğünde her piksel taraması maliyetli; step=2 doğruluğu bozmaz
        // çünkü dolu kabarcığın yarıçapı zaten 10-15 px (Nyquist içi).
        int step = searchRadius >= 5 ? 2 : 1;
        float best = 1f;
        int sr2 = searchRadius * searchRadius;
        for (int dy = -searchRadius; dy <= searchRadius; dy += step) {
            for (int dx = -searchRadius; dx <= searchRadius; dx += step) {
                if (dx * dx + dy * dy > sr2) continue;
                float br = sampleBrightness(pixels,
                    cx + dx, cy + dy, sampleRadius, imgW, imgH);
                if (br < best) best = br;
            }
        }
        return best;
    }

    // ════════════════════════════════════════════════════════════════════════
    //                  REAL-TIME API (canlı kamera kareleri)
    // ════════════════════════════════════════════════════════════════════════

    private static float cornerDist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Köşe adayı kağıdın ilgili köşesine yeterince yakın mı? (içteki koyu baloncukları elemek için). */
    private static boolean isCornerCandidateNearExpected(PointF p, int cornerIndex,
            int pl, int ptop, int pr, int pb, int expectedMarkerPx) {
        if (p == null) return false;
        float tx = (cornerIndex == 0 || cornerIndex == 2) ? pl : pr;
        float ty = (cornerIndex == 0 || cornerIndex == 1) ? ptop : pb;
        float dx = p.x - tx;
        float dy = p.y - ty;
        // Marker beklenen konumunun euclidean mesafesi = mcPx*√2 ≈ markerPx*1.49.
        // ±3×markerPx toleransla kabul aralığı = markerPx * (1.49 + 3) * 1.41 ≈ markerPx * 6.3.
        // Eski 7.0 katsayısı + %30 kağıt genişliği (600px!) yanlış konumları da kabul ediyordu.
        float maxDist = Math.max(expectedMarkerPx * 5.2f,
            Math.min(pr - pl, pb - ptop) * 0.14f);
        return dx * dx + dy * dy <= maxDist * maxDist;
    }

    /**
     * Üç köşe güvenilirken dördüncüyü paralelkenar kuralıyla tamamlar (TR+BL-TL=BR vb.),
     * ardından küçük pencerede {@link #findMarkerCenter} ile gerçek siyah kareye yaklaştırır.
     */
    private static void tryCompleteMissingCorner(PointF[] c, int[] pixels, int imgW, int imgH,
                                                 int expectedMarkerPx) {
        int nullIdx = -1;
        int nullCount = 0;
        for (int i = 0; i < 4; i++) {
            if (c[i] == null) {
                nullCount++;
                nullIdx = i;
            }
        }
        if (nullCount != 1) return;

        PointF guess = null;
        if (nullIdx == 3 && c[0] != null && c[1] != null && c[2] != null) {
            guess = new PointF(c[1].x + c[2].x - c[0].x, c[1].y + c[2].y - c[0].y);
            float topW = cornerDist(c[0], c[1]);
            float leftH = cornerDist(c[0], c[2]);
            if (topW < 24f || leftH < 24f) return;
            if (Math.abs(cornerDist(c[2], guess) - topW) > 0.55f * topW) return;
            if (Math.abs(cornerDist(c[1], guess) - leftH) > 0.55f * leftH) return;
        } else if (nullIdx == 1 && c[0] != null && c[2] != null && c[3] != null) {
            guess = new PointF(c[3].x + c[0].x - c[2].x, c[3].y + c[0].y - c[2].y);
            float dTop = cornerDist(c[0], guess);
            float dBot = cornerDist(c[2], c[3]);
            float dL = cornerDist(c[0], c[2]);
            float dR = cornerDist(guess, c[3]);
            if (dBot < 24f || dL < 24f) return;
            if (Math.abs(dTop - dBot) > 0.55f * dBot) return;
            if (Math.abs(dL - dR) > 0.55f * dL) return;
        } else if (nullIdx == 2 && c[0] != null && c[1] != null && c[3] != null) {
            guess = new PointF(c[3].x + c[0].x - c[1].x, c[3].y + c[0].y - c[1].y);
            float leftH = cornerDist(c[0], guess);
            float rightH = cornerDist(c[1], c[3]);
            float topW = cornerDist(c[0], c[1]);
            float botW = cornerDist(guess, c[3]);
            if (rightH < 24f || topW < 24f) return;
            if (Math.abs(leftH - rightH) > 0.55f * rightH) return;
            if (Math.abs(topW - botW) > 0.55f * topW) return;
        } else if (nullIdx == 0 && c[1] != null && c[2] != null && c[3] != null) {
            guess = new PointF(c[1].x + c[2].x - c[3].x, c[1].y + c[2].y - c[3].y);
            float topW = cornerDist(guess, c[1]);
            float botW = cornerDist(c[2], c[3]);
            float leftH = cornerDist(guess, c[2]);
            float rightH = cornerDist(c[1], c[3]);
            if (botW < 24f || leftH < 24f) return;
            if (Math.abs(topW - botW) > 0.55f * botW) return;
            if (Math.abs(leftH - rightH) > 0.55f * leftH) return;
        }
        if (guess == null) return;

        guess.x = Math.max(2f, Math.min(imgW - 3f, guess.x));
        guess.y = Math.max(2f, Math.min(imgH - 3f, guess.y));

        int r = Math.max(22, Math.round(expectedMarkerPx * 2.6f));
        int x0 = Math.max(0, (int) guess.x - r);
        int y0 = Math.max(0, (int) guess.y - r);
        int x1 = Math.min(imgW, (int) guess.x + r + 1);
        int y1 = Math.min(imgH, (int) guess.y + r + 1);
        if (x1 > x0 && y1 > y0) {
            PointF refined = findMarkerCenter(pixels, imgW, imgH, x0, y0, x1, y1, expectedMarkerPx);
            c[nullIdx] = refined;
        }
    }

    /** Son çare: görüntünün ilgili çeyreğinde marker ara (boyut kontrolü gevşek). */
    private static PointF tryQuadrantFallback(int[] pixels, int imgW, int imgH, int cornerIndex) {
        int mx = Math.max(56, imgW * 3 / 10);
        int my = Math.max(56, imgH * 3 / 10);
        int x0, y0, x1, y1;
        switch (cornerIndex) {
            case 0: x0 = 0;       y0 = 0;       x1 = mx;     y1 = my;     break;
            case 1: x0 = imgW - mx; y0 = 0;     x1 = imgW;   y1 = my;     break;
            case 2: x0 = 0;       y0 = imgH - my; x1 = mx;     y1 = imgH;   break;
            default: x0 = imgW - mx; y0 = imgH - my; x1 = imgW;   y1 = imgH;   break;
        }
        if (x1 <= x0 || y1 <= y0) return null;
        PointF p = findMarkerCenter(pixels, imgW, imgH, x0, y0, x1, y1, 0);
        return p;
    }

    /**
     * Verilen kağıt dikdörtgeni içinde 4 köşe marker'ını arar (önizleme ve tam çözünürlük ortak).
     */
    private static PointF[] findFourCornerMarkersInPaperRect(int[] pixels, int imgW, int imgH,
            int pl, int ptop, int pr, int pb, int paperW, int expectedMarkerPx,
            int pdfW, int pdfH) {
        int paperH = pb - ptop;

        // Marker merkezi, sayfa boyutuna oranlı kenar payı + marker yarıçapı kadar içerdedir.
        float mcPt = PdfGenerator.getMarkerCenterOffsetPt(pdfW, pdfH);
        int mcPx = Math.max(12, Math.round(mcPt * paperW / (float) pdfW));
        int searchR = Math.max(expectedMarkerPx * 2, Math.min(paperW, paperH) / 6);

        int expTlX = pl + mcPx, expTlY = ptop + mcPx;
        int expTrX = pr - mcPx, expTrY = ptop + mcPx;
        int expBlX = pl + mcPx, expBlY = pb - mcPx;
        int expBrX = pr - mcPx, expBrY = pb - mcPx;

        PointF[] corners = new PointF[4];
        corners[0] = findMarkerNearExpectedCenter(pixels, imgW, imgH, expTlX, expTlY, searchR, expectedMarkerPx);
        corners[1] = findMarkerNearExpectedCenter(pixels, imgW, imgH, expTrX, expTrY, searchR, expectedMarkerPx);
        corners[2] = findMarkerNearExpectedCenter(pixels, imgW, imgH, expBlX, expBlY, searchR, expectedMarkerPx);
        corners[3] = findMarkerNearExpectedCenter(pixels, imgW, imgH, expBrX, expBrY, searchR, expectedMarkerPx);

        for (int i = 0; i < 4; i++) {
            if (!isCornerCandidateNearExpected(corners[i], i, pl, ptop, pr, pb, expectedMarkerPx)) {
                corners[i] = null;
            }
        }

        tryCompleteMissingCorner(corners, pixels, imgW, imgH, expectedMarkerPx);

        // Bilinçli olarak quadrant fallback kapalı:
        // Yanlış yerde bir koyu lekeyi marker sanmaktansa "köşe bulunamadı" daha güvenli.
        return corners;
    }

    /**
     * Canlı kamera karesinden 4 köşe markerını bulur.
     * Kağıt tespiti + paper-bounded marker arama uygulanır.
     * Dönüş: 4 PointF [TL, TR, BL, BR] (her biri null olabilir).
     */
    public static PointF[] detectCornersPartial(int[] pixels, int imgW, int imgH,
                                                int pdfW, int pdfH) {
        int[] paper = detectPaperBounds(pixels, imgW, imgH);
        int pl = paper[0], ptop = paper[1], pr = paper[2], pb = paper[3];
        int paperW = pr - pl;
        int paperH = pb - ptop;
        if (paperW < imgW / 4 || paperH < imgH / 4) {
            pl = 0; ptop = 0; pr = imgW; pb = imgH;
            paperW = imgW;
        }
        int expectedMarkerPx = Math.max(8, Math.round(paperW * (PdfGenerator.MARKER_PT / (float) pdfW)));
        return findFourCornerMarkersInPaperRect(pixels, imgW, imgH,
            pl, ptop, pr, pb, paperW, expectedMarkerPx, pdfW, pdfH);
    }

    /**
     * Y-plane (luminance) byte buffer'ından partial 4 köşe markerını bulur.
     * @param scratchBuffer dışarıdan verilen, en az imgW*imgH boyutlu int[] buffer
     *                     (her frame yeni allocate'i önler). null verilirse her çağrıda yeni allocate.
     */
    public static PointF[] detectCornersFromLumaPartial(byte[] luma, int rowStride,
                                                       int imgW, int imgH,
                                                       int pdfW, int pdfH,
                                                       int[] scratchBuffer) {
        int needed = imgW * imgH;
        int[] pixels = (scratchBuffer != null && scratchBuffer.length >= needed)
            ? scratchBuffer : new int[needed];
        for (int y = 0; y < imgH; y++) {
            int srcBase = y * rowStride;
            int dstBase = y * imgW;
            for (int x = 0; x < imgW; x++) {
                int v = luma[srcBase + x] & 0xFF;
                pixels[dstBase + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return detectCornersPartial(pixels, imgW, imgH, pdfW, pdfH);
    }

    /**
     * {@link #detectCornersFromLumaPartial} ile doldurulmuş ARGB scratch üzerinde
     * tam görüntü işlemeyle aynı kağıt-içi parlaklık yayılımı (0..1).
     * Kağıt güvenilir bulunamazsa 0 — önizlemede yanlışlıkla kilitleme yapılmaz.
     */
    public static float previewIlluminationSpread(int[] argbPixels, int imgW, int imgH) {
        if (argbPixels == null || imgW < 32 || imgH < 32) return 0f;
        int[] paper = detectPaperBounds(argbPixels, imgW, imgH);
        int pl = paper[0], ptop = paper[1], pr = paper[2], pb = paper[3];
        int paperW = pr - pl, paperH = pb - ptop;
        if (paperW < imgW / 4 || paperH < imgH / 4) return 0f;
        return computePaperIlluminationSpread(argbPixels, imgW, imgH, pl, ptop, pr, pb);
    }

    /** Canlı önizlemede gölge eşiği — geri sayım / otomatik çekim durdurulur. */
    public static boolean isPreviewBlockedByShadow(int[] argbPixels, int imgW, int imgH) {
        return previewIlluminationSpread(argbPixels, imgW, imgH) >= SHADOW_SPREAD_THRESHOLD;
    }
}
