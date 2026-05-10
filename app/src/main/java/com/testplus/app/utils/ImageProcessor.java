package com.testplus.app.utils;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenCV belge tarayıcı hattı: dörtgen bulma, {@link Imgproc#getPerspectiveTransform} +
 * {@link Imgproc#warpPerspective}, gri ton ve isteğe bağlı {@link Imgproc#adaptiveThreshold}.
 *
 * <p><b>OpenScan benzeri tespit:</b> {@code findQuadOpenScanStyle} — ethereal-developers/OpenScan
 * {@code scan.cpp} içindeki {@code getPoints} yaklaşımı (çok kanallı Canny + sabit eşik,
 * {@code approxPolyDP}, kosinüs filtresi, en büyük dörtgen). Perspektif düzeltme ve OpenScan
 * {@code getBWBitmap} ile uyumlu adaptif eşik parametreleri.</p>
 *
 * <p><b>OMR uyarısı:</b> {@link #scanDocumentBinarized(Bitmap)} siyah-beyaz ikidir; kabarcık
 * parlaklık analizi için doğrudan OMR’ye vermeyin. {@link #scanDocumentWarpForOmr(Bitmap)} warp +
 * CLAHE ile ton sürekliliğini korur.</p>
 *
 * <p><b>Kamera — {@code ImageCapture.OnImageSavedCallback} içinde:</b></p>
 * <pre>{@code
 * Bitmap bmp = BitmapFactory.decodeFile(path);
 * bmp = applyExifRotation(bmp, path);
 * if (ImageProcessor.initOpenCv()) {
 *     Bitmap fixed = ImageProcessor.scanDocumentWarpForOmr(bmp);
 *     if (fixed != null) {
 *         bmp.recycle();
 *         bmp = fixed;
 *     }
 * }
 * processBitmap(bmp);
 * }</pre>
 */
public final class ImageProcessor {

    private static final String TAG = "ImageProcessor";
    private static volatile boolean openCvInitialized = false;

    private ImageProcessor() {}

    public static synchronized boolean initOpenCv() {
        if (openCvInitialized) return true;
        try {
            openCvInitialized = OpenCVLoader.initDebug();
            if (!openCvInitialized) Log.e(TAG, "OpenCVLoader.initDebug() başarısız");
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV yükleme hatası", t);
            openCvInitialized = false;
        }
        return openCvInitialized;
    }

    /**
     * Perspektif düzeltilmiş renkli görüntü — mevcut OMR ile uyumlu (sürekli ton).
     */
    public static Bitmap scanDocumentWarpForOmr(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        if (!openCvInitialized && !initOpenCv()) return null;

        Mat rgba = new Mat();
        Mat warped = null;
        try {
            Utils.bitmapToMat(source, rgba);
            if (rgba.empty()) return null;

            // 1) OpenScan tarzı tam sayfa dörtgeni (genelde optik sayfada en stabil)
            Point[] quad = findQuadOpenScanStyle(rgba);
            if (quad != null) {
                Log.i(TAG, "Belge dörtgeni: OpenScan tarzı tespit");
            }
            // 2) Klasik tek gri kanal Canny + en büyük kontur
            if (quad == null) {
                quad = findBestDocumentQuad(rgba);
                if (quad != null) Log.i(TAG, "Belge dörtgeni: standart Canny kontur");
            }
            // 3) Optik köşe markerları
            if (quad == null) {
                quad = findQuadFromCornerMarkers(rgba, source);
                if (quad != null) Log.i(TAG, "Belge dörtgeni: köşe marker fallback");
            }
            if (quad == null) {
                Log.w(TAG, "Belge/marker dörtgeni bulunamadı — warp atlandı");
                return null;
            }

            warped = warpPerspectiveToRectangle(rgba, quad);
            if (warped == null || warped.empty()) return null;

            // Gölge / düzensiz aydınlatmayı yumuşat (OpenScan’daki “temiz kağıt” hissi);
            // ikili adaptiveThreshold yerine LAB+L kanalında CLAHE — kabarcık tonları korunur.
            applyClaheShadowNormalize(warped);

            Bitmap out = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(warped, out);
            return out;
        } finally {
            rgba.release();
            if (warped != null) warped.release();
        }
    }

    /**
     * Köşe marker merkezlerinden sayfa köşelerini yaklaşıklar.
     * Dönüş sırası: TL, TR, BR, BL.
     */
    private static Point[] findQuadFromCornerMarkers(Mat rgba, Bitmap source) {
        Point[] fromOmr = findQuadWithOmrDetector(source);
        if (fromOmr != null) {
            Log.i(TAG, "Marker fallback: OMR köşe tespiti kullanıldı");
            return fromOmr;
        }

        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            int w = gray.cols();
            int h = gray.rows();
            if (w < 40 || h < 40) return null;

            Point tl = detectCornerMarker(gray, 0, 0, w, h);
            Point tr = detectCornerMarker(gray, 1, 0, w, h);
            Point bl = detectCornerMarker(gray, 0, 1, w, h);
            Point br = detectCornerMarker(gray, 1, 1, w, h);
            if (tl == null || tr == null || bl == null || br == null) return null;

            // Marker merkezlerini, sayfa köşelerine doğru hafifçe dışa doğru genişlet.
            // r: marker-merkezinin kenardan normalize uzaklığı (~2-4% aralığı).
            final double r = 0.03;
            double k = 1.0 / (1.0 - 2.0 * r); // merkezler arası mesafeden tam sayfa boyuna geçiş

            Point center = new Point((tl.x + tr.x + bl.x + br.x) * 0.25, (tl.y + tr.y + bl.y + br.y) * 0.25);
            Point[] markerQuad = new Point[]{tl, tr, br, bl};
            Point[] pageQuad = new Point[4];
            for (int i = 0; i < 4; i++) {
                double dx = markerQuad[i].x - center.x;
                double dy = markerQuad[i].y - center.y;
                pageQuad[i] = new Point(center.x + dx * k, center.y + dy * k);
            }
            return orderPoints(pageQuad);
        } catch (Throwable t) {
            Log.w(TAG, "Marker fallback başarısız", t);
            return null;
        } finally {
            gray.release();
        }
    }

    /** OmrProcessor'ın daha toleranslı köşe tespitiyle marker merkezlerinden sayfa quad üretir. */
    private static Point[] findQuadWithOmrDetector(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int w = source.getWidth();
        int h = source.getHeight();
        if (w < 32 || h < 32) return null;

        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);

        int[][] sizes = new int[][]{
            {PdfGenerator.A4_W, PdfGenerator.A4_H},
            {PdfGenerator.A4_H, PdfGenerator.A4_W},
            {PdfGenerator.A5_W, PdfGenerator.A5_H},
            {PdfGenerator.A5_H, PdfGenerator.A5_W},
            {PdfGenerator.A6_W, PdfGenerator.A6_H},
            {PdfGenerator.A6_H, PdfGenerator.A6_W}
        };

        PointF[] bestCorners = null;
        int bestPdfW = PdfGenerator.A4_W;
        int bestPdfH = PdfGenerator.A4_H;
        double bestArea = -1.0;

        for (int[] s : sizes) {
            int pdfW = s[0], pdfH = s[1];
            PointF[] c = OmrProcessor.detectCornersPartial(pixels, w, h, pdfW, pdfH);
            if (c == null || c.length < 4 || c[0] == null || c[1] == null || c[2] == null || c[3] == null) {
                continue;
            }
            double area = quadArea(c[0], c[1], c[3], c[2]);
            if (area > bestArea) {
                bestArea = area;
                bestCorners = c;
                bestPdfW = pdfW;
                bestPdfH = pdfH;
            }
        }

        if (bestCorners == null) return null;

        Point tl = new Point(bestCorners[0].x, bestCorners[0].y);
        Point tr = new Point(bestCorners[1].x, bestCorners[1].y);
        Point bl = new Point(bestCorners[2].x, bestCorners[2].y);
        Point br = new Point(bestCorners[3].x, bestCorners[3].y);

        // Marker merkezi -> sayfa köşesi dönüşümü (pdf oranına göre normalize).
        double r = PdfGenerator.getMarkerCenterOffsetPt(bestPdfW, bestPdfH) / (double) bestPdfW;
        r = Math.max(0.015, Math.min(0.08, r));
        double k = 1.0 / (1.0 - 2.0 * r);
        Point center = new Point((tl.x + tr.x + bl.x + br.x) * 0.25, (tl.y + tr.y + bl.y + br.y) * 0.25);

        Point[] markerOrdered = new Point[]{tl, tr, br, bl};
        Point[] pageQuad = new Point[4];
        for (int i = 0; i < 4; i++) {
            double dx = markerOrdered[i].x - center.x;
            double dy = markerOrdered[i].y - center.y;
            pageQuad[i] = new Point(center.x + dx * k, center.y + dy * k);
        }
        return orderPoints(pageQuad);
    }

    /**
     * OpenScan {@code scan.cpp#getPoints}: çok kanallı arama, Canny(75,175)+dilate ve sabit eşik,
     * {@code approxPolyDP} (0.02·çevre), konveks dörtgen + kosinüs &lt; 0.3 — en büyük alan.
     */
    private static Point[] findQuadOpenScanStyle(Mat rgbaFull) {
        final int maxDetectDim = 800;
        int W = rgbaFull.cols();
        int H = rgbaFull.rows();
        double scale = 1.0;
        Mat work = rgbaFull;
        Mat resized = null;
        try {
            int maxSide = Math.max(W, H);
            if (maxSide > maxDetectDim) {
                scale = maxDetectDim / (double) maxSide;
                int nw = Math.max(4, (int) Math.round(W * scale));
                int nh = Math.max(4, (int) Math.round(H * scale));
                resized = new Mat();
                Imgproc.resize(rgbaFull, resized, new Size(nw, nh));
                work = resized;
            }
            double imgArea = work.rows() * (double) work.cols();
            double minQuadArea = Math.max(1000.0, imgArea * 0.02);

            Mat blurred = new Mat();
            Imgproc.GaussianBlur(work, blurred, new Size(5, 5), 0);

            Point[] bestPts = null;
            double bestArea = 0.0;

            Mat gray0 = new Mat();
            Mat gray = new Mat();
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));

            try {
                for (int c = 0; c < 3; c++) {
                    Core.extractChannel(blurred, gray0, c);
                    for (int level = 0; level < 2; level++) {
                        if (level == 0) {
                            Imgproc.Canny(gray0, gray, 75, 175, 3, false);
                            Imgproc.dilate(gray, gray, kernel);
                        } else {
                            Imgproc.threshold(gray0, gray, 127, 255, Imgproc.THRESH_BINARY);
                        }

                        List<MatOfPoint> contours = new ArrayList<>();
                        Mat hierarchy = new Mat();
                        Imgproc.findContours(gray, contours, hierarchy,
                            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
                        hierarchy.release();

                        for (MatOfPoint cnt : contours) {
                            MatOfPoint2f c2f = new MatOfPoint2f(cnt.toArray());
                            MatOfPoint2f approx = new MatOfPoint2f();
                            try {
                                double peri = Imgproc.arcLength(c2f, true);
                                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);
                                Point[] pts = approx.toArray();
                                if (pts.length != 4) continue;

                                double area = Math.abs(Imgproc.contourArea(approx));
                                if (area < minQuadArea) continue;

                                MatOfPoint mpApprox = new MatOfPoint(approx.toArray());
                                if (!Imgproc.isContourConvex(mpApprox)) continue;

                                double maxCos = 0.0;
                                for (int j = 2; j < 5; j++) {
                                    double cos = Math.abs(angleOpenScan(
                                        pts[j % 4], pts[j - 2], pts[j - 1]));
                                    maxCos = Math.max(maxCos, cos);
                                }
                                if (maxCos >= 0.3) continue;

                                if (area > bestArea) {
                                    bestArea = area;
                                    bestPts = new Point[]{
                                        new Point(pts[0].x, pts[0].y),
                                        new Point(pts[1].x, pts[1].y),
                                        new Point(pts[2].x, pts[2].y),
                                        new Point(pts[3].x, pts[3].y)
                                    };
                                }
                            } finally {
                                c2f.release();
                                approx.release();
                            }
                        }
                    }
                }
            } finally {
                blurred.release();
                gray0.release();
                gray.release();
                kernel.release();
            }

            if (bestPts == null) return null;

            if (scale < 1.0 - 1e-6) {
                double inv = 1.0 / scale;
                for (Point p : bestPts) {
                    p.x *= inv;
                    p.y *= inv;
                }
            }
            return orderPoints(bestPts);
        } finally {
            if (resized != null) resized.release();
        }
    }

    /** OpenScan {@code angle()} — üç noktada kosinüs (köşe dikliği). */
    private static double angleOpenScan(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        double denom = Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2)) + 1e-10;
        return (dx1 * dx2 + dy1 * dy2) / denom;
    }

    /**
     * LAB uzayı L kanalında CLAHE — gölgeleri yumuşatır; sürekli ton korunur (OMR için).
     */
    private static void applyClaheShadowNormalize(Mat rgba) {
        if (rgba == null || rgba.empty()) return;
        Mat bgr = new Mat();
        Mat lab = new Mat();
        Mat mergedLab = new Mat();
        List<Mat> planes = new ArrayList<>(3);
        try {
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
            Core.split(lab, planes);
            if (planes.isEmpty()) return;
            org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            clahe.apply(planes.get(0), planes.get(0));
            Core.merge(planes, mergedLab);
            Imgproc.cvtColor(mergedLab, bgr, Imgproc.COLOR_Lab2BGR);
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
        } finally {
            bgr.release();
            lab.release();
            mergedLab.release();
            for (Mat p : planes) {
                if (p != null) p.release();
            }
        }
    }

    private static double quadArea(PointF tl, PointF tr, PointF br, PointF bl) {
        double a1 = Math.abs(
            tl.x * tr.y + tr.x * br.y + br.x * tl.y
            - tr.x * tl.y - br.x * tr.y - tl.x * br.y
        ) * 0.5;
        double a2 = Math.abs(
            tl.x * br.y + br.x * bl.y + bl.x * tl.y
            - br.x * tl.y - bl.x * br.y - tl.x * bl.y
        ) * 0.5;
        return a1 + a2;
    }

    /** cornerX: 0=left,1=right; cornerY: 0=top,1=bottom */
    private static Point detectCornerMarker(Mat gray, int cornerX, int cornerY, int fullW, int fullH) {
        int roiW = Math.max(20, (int) Math.round(fullW * 0.22));
        int roiH = Math.max(20, (int) Math.round(fullH * 0.22));
        int x0 = (cornerX == 0) ? 0 : (fullW - roiW);
        int y0 = (cornerY == 0) ? 0 : (fullH - roiH);

        Mat roi = gray.submat(y0, y0 + roiH, x0, x0 + roiW);
        Mat bin = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        try {
            double mean = org.opencv.core.Core.mean(roi).val[0];
            double thresh = Math.max(35.0, Math.min(110.0, mean * 0.55));
            Imgproc.threshold(roi, bin, thresh, 255, Imgproc.THRESH_BINARY_INV);

            int n = Imgproc.connectedComponentsWithStats(bin, labels, stats, centroids, 8);
            if (n <= 1) return null;

            int bestIdx = -1;
            double bestScore = -1;
            double minArea = fullW * fullH * 0.00002; // marker çok küçük olabilir
            double maxArea = fullW * fullH * 0.02;

            for (int i = 1; i < n; i++) {
                double area = stats.get(i, Imgproc.CC_STAT_AREA)[0];
                if (area < minArea || area > maxArea) continue;
                double bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
                double bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
                if (bw < 3 || bh < 3) continue;
                double ratio = bw / bh;
                if (ratio < 0.35 || ratio > 2.8) continue;
                double squareness = 1.0 - Math.min(1.0, Math.abs(1.0 - ratio));
                double score = area * (0.6 + 0.4 * squareness);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }

            if (bestIdx < 0) return null;
            double cx = centroids.get(bestIdx, 0)[0];
            double cy = centroids.get(bestIdx, 1)[0];
            return new Point(x0 + cx, y0 + cy);
        } finally {
            roi.release();
            bin.release();
            labels.release();
            stats.release();
            centroids.release();
        }
    }

    /**
     * Perspektif + grayscale + adaptif eşik — belge önizleme / OCR; OMR için genelde kullanmayın.
     */
    public static Bitmap scanDocumentBinarized(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        if (!openCvInitialized && !initOpenCv()) return null;

        Mat rgba = new Mat();
        Mat warped = null;
        Mat gray = new Mat();
        Mat binary = new Mat();
        Mat rgbaOut = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            if (rgba.empty()) return null;

            Point[] quad = findQuadOpenScanStyle(rgba);
            if (quad == null) quad = findBestDocumentQuad(rgba);
            Mat srcForPipe = rgba;
            if (quad != null) {
                warped = warpPerspectiveToRectangle(rgba, quad);
                if (warped != null && !warped.empty()) srcForPipe = warped;
            }

            // OpenScan getBWBitmap: blur(2x2) + adaptiveThreshold(GAUSSIAN, 7, 2) + bitwise_not
            Imgproc.cvtColor(srcForPipe, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.blur(gray, gray, new Size(2, 2));
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                7,
                2
            );
            Core.bitwise_not(binary, binary);
            Imgproc.cvtColor(binary, rgbaOut, Imgproc.COLOR_GRAY2RGBA);

            Bitmap out = Bitmap.createBitmap(rgbaOut.cols(), rgbaOut.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbaOut, out);
            return out;
        } finally {
            rgba.release();
            if (warped != null) warped.release();
            gray.release();
            binary.release();
            rgbaOut.release();
        }
    }

    private static Point[] findBestDocumentQuad(Mat bgr) {
        final int maxDetectDim = 800;
        int W = bgr.cols();
        int H = bgr.rows();
        double scale = 1.0;
        Mat work = bgr;
        Mat resized = null;

        try {
            int maxSide = Math.max(W, H);
            if (maxSide > maxDetectDim) {
                scale = maxDetectDim / (double) maxSide;
                int nw = Math.max(4, (int) Math.round(W * scale));
                int nh = Math.max(4, (int) Math.round(H * scale));
                resized = new Mat();
                Imgproc.resize(bgr, resized, new Size(nw, nh));
                work = resized;
            }

            Mat gray = new Mat();
            Mat blurred = new Mat();
            Mat edges = new Mat();
            try {
                Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
                Imgproc.Canny(blurred, edges, 75, 200);

                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
                Imgproc.dilate(edges, edges, kernel);
                kernel.release();

                List<MatOfPoint> contours = new ArrayList<>();
                Mat hierarchy = new Mat();
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
                hierarchy.release();

                double imgArea = work.rows() * (double) work.cols();
                double minArea = imgArea * 0.15;
                double bestArea = 0;
                Point[] bestQuad = null;

                for (MatOfPoint cnt : contours) {
                    double area = Imgproc.contourArea(cnt);
                    if (area < minArea) continue;

                    MatOfPoint2f c2f = new MatOfPoint2f(cnt.toArray());
                    MatOfPoint2f approx = new MatOfPoint2f();
                    try {
                        double peri = Imgproc.arcLength(c2f, true);
                        Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

                        Point[] pts = approx.toArray();
                        if (pts.length == 4) {
                            double qArea = Imgproc.contourArea(approx);
                            if (qArea > bestArea) {
                                bestArea = qArea;
                                bestQuad = pts.clone();
                            }
                        }
                    } finally {
                        c2f.release();
                        approx.release();
                    }
                }

                if (bestQuad == null) return null;

                if (scale < 1.0 - 1e-6) {
                    double inv = 1.0 / scale;
                    for (Point p : bestQuad) {
                        p.x *= inv;
                        p.y *= inv;
                    }
                }
                return orderPoints(bestQuad);
            } finally {
                gray.release();
                blurred.release();
                edges.release();
            }
        } finally {
            if (resized != null) resized.release();
        }
    }

    /** Köşe sırası: TL, TR, BR, BL (hedef dikdörtgen ile uyumlu). */
    private static Point[] orderPoints(Point[] pts) {
        int iTL = 0, iBR = 0;
        for (int i = 1; i < 4; i++) {
            double s = pts[i].x + pts[i].y;
            if (s < pts[iTL].x + pts[iTL].y) iTL = i;
            if (s > pts[iBR].x + pts[iBR].y) iBR = i;
        }
        int a = -1, b = -1;
        for (int i = 0; i < 4; i++) {
            if (i != iTL && i != iBR) {
                if (a < 0) a = i;
                else b = i;
            }
        }
        Point pa = pts[a];
        Point pb = pts[b];
        // TR: daha küçük (y−x) — üst-sağ; BL: daha büyük — alt-sol
        Point tr = (pa.y - pa.x < pb.y - pb.x) ? pa : pb;
        Point bl = (tr == pa) ? pb : pa;
        return new Point[]{pts[iTL], tr, pts[iBR], bl};
    }

    private static Mat warpPerspectiveToRectangle(Mat srcBgr, Point[] ordered) {
        Point tl = ordered[0];
        Point tr = ordered[1];
        Point br = ordered[2];
        Point bl = ordered[3];

        double widthTop = Math.hypot(tr.x - tl.x, tr.y - tl.y);
        double widthBot = Math.hypot(br.x - bl.x, br.y - bl.y);
        double maxWidth = Math.max(widthTop, widthBot);

        double heightLeft = Math.hypot(bl.x - tl.x, bl.y - tl.y);
        double heightRight = Math.hypot(br.x - tr.x, br.y - tr.y);
        double maxHeight = Math.max(heightLeft, heightRight);

        int outW = Math.max(2, (int) Math.round(maxWidth));
        int outH = Math.max(2, (int) Math.round(maxHeight));

        MatOfPoint2f srcPts = new MatOfPoint2f(tl, tr, br, bl);
        MatOfPoint2f dstPts = new MatOfPoint2f(
            new Point(0, 0),
            new Point(outW - 1, 0),
            new Point(outW - 1, outH - 1),
            new Point(0, outH - 1)
        );

        Mat M = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(srcBgr, dst, M, new Size(outW, outH));

        M.release();
        srcPts.release();
        dstPts.release();
        return dst;
    }
}
