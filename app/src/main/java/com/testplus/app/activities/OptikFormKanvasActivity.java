package com.testplus.app.activities;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.OptikForm;
import com.testplus.app.database.entities.OptikFormAlan;
import com.testplus.app.database.entities.Sinav;
import com.testplus.app.utils.Constants;
import com.testplus.app.utils.PdfGenerator;
import com.testplus.app.views.IsaretlemeAlanView;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OptikFormKanvasActivity extends AppCompatActivity {

    private static final int GRID_DP = 24;
    private static final int REQUEST_ADD = 1001;
    private static final int REQUEST_EDIT = 1002;
    private static final int REQUEST_WRITE_STORAGE_GALLERY = 3001;

    private FrameLayout kanvasLayout;
    private View canvasViewport;
    private long formId;
    private OptikForm optikForm;
    private AppDatabase db;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private float density;
    private float canvasScale = 1f; // ekran genişliğine göre A4 küçültme oranı
    private float fieldScale = 1f; // kağıt boyutuna göre alan ölçeği
    private int canvasWidthPx, canvasHeightPx;
    private String exportTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_optik_form_kanvas);
        density = getResources().getDisplayMetrics().density;
        db = AppDatabase.getInstance(this);
        formId = getIntent().getLongExtra(Constants.EXTRA_OPTIK_FORM_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        String formAdi = getIntent().getStringExtra("form_adi");
        getSupportActionBar().setTitle(formAdi != null ? formAdi : "Optik Form");
        toolbar.setNavigationIcon(R.drawable.ic_close);
        toolbar.setNavigationOnClickListener(v -> finish());

        kanvasLayout = findViewById(R.id.kanvasLayout);
        canvasViewport = findViewById(R.id.canvasViewport);

        // Save button
        TextView tvKaydet = findViewById(R.id.tvKaydet);
        tvKaydet.setOnClickListener(v -> kaydet());

        // PDF export button
        TextView tvPdf = findViewById(R.id.tvPdf);
        tvPdf.setOnClickListener(v -> exportPdf());

        TextView tvGaleri = findViewById(R.id.tvGaleri);
        if (tvGaleri != null) tvGaleri.setOnClickListener(v -> exportToGallery());

        FloatingActionButton fab = findViewById(R.id.fabEkle);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(this, YeniOptikFormAlanActivity.class);
            intent.putExtra(Constants.EXTRA_OPTIK_FORM_ID, formId);
            startActivityForResult(intent, REQUEST_ADD);
        });

        executor.execute(() -> {
            optikForm = db.optikFormDao().getById(formId);
            exportTitle = resolveExportTitle();
            runOnUiThread(() -> {
                if (optikForm != null) {
                    setupCanvas(optikForm);
                } else {
                    yukleAlanlar();
                }
            });
        });
    }

    private void setupCanvas(OptikForm form) {
        int wDp = PdfGenerator.getCanvasWidthDp(form);
        int hDp = PdfGenerator.getCanvasHeightDp(form);
        fieldScale = PdfGenerator.getFieldScaleForPage(
            PdfGenerator.getPdfWidth(form), PdfGenerator.getPdfHeight(form));
        canvasViewport.post(() -> {
            int idealWidthPx = dpToPx(wDp);
            int idealHeightPx = dpToPx(hDp);
            int availableWidthPx = Math.max(1, canvasViewport.getWidth() - canvasViewport.getPaddingLeft() - canvasViewport.getPaddingRight());
            int availableHeightPx = Math.max(1, canvasViewport.getHeight() - canvasViewport.getPaddingTop() - canvasViewport.getPaddingBottom());

            // Kağıt her zaman ekrana tamamen sığsın; sağa kaydırma olmasın.
            float scaleW = (float) availableWidthPx / idealWidthPx;
            float scaleH = (float) availableHeightPx / idealHeightPx;
            canvasScale = Math.min(scaleW, scaleH);

            canvasWidthPx = Math.round(idealWidthPx * canvasScale);
            canvasHeightPx = Math.round(idealHeightPx * canvasScale);

            kanvasLayout.setMinimumWidth(canvasWidthPx);
            kanvasLayout.setMinimumHeight(canvasHeightPx);

            float gridPx = getGridStepPx();
            int pdfW = PdfGenerator.getPdfWidth(form);
            int pdfH = PdfGenerator.getPdfHeight(form);
            int canvasWdp = PdfGenerator.getCanvasWidthDp(form);
            float ptToDpX = canvasWdp / (float) pdfW;
            float markerSizePx = PdfGenerator.MARKER_PT * ptToDpX * density * canvasScale;
            kanvasLayout.setBackground(new GridDrawable(
                gridPx, getGridOriginX(), getGridOriginY(),
                getSafeInsetXPx(), getSafeInsetYPx(), markerSizePx));

            ViewGroup.LayoutParams lp = kanvasLayout.getLayoutParams();
            if (lp != null) {
                lp.width = canvasWidthPx;
                lp.height = canvasHeightPx;
                kanvasLayout.setLayoutParams(lp);
            }
            yukleAlanlar();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) yukleAlanlar();
    }

    private void yukleAlanlar() {
        executor.execute(() -> {
            List<OptikFormAlan> alanlar = db.optikFormAlanDao().getByFormId(formId);
            runOnUiThread(() -> {
                kanvasLayout.removeAllViews();
                for (OptikFormAlan alan : alanlar) addAlanView(alan);
            });
        });
    }

    private void addAlanView(OptikFormAlan alan) {
        IsaretlemeAlanView view = new IsaretlemeAlanView(this, alan);
        view.setFieldScale(fieldScale);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        view.setLayoutParams(lp);
        // Kanvas ölçeğine göre küçült: pivot sol-üst, böylece (x,y) doğru kalır
        view.setPivotX(0f);
        view.setPivotY(0f);
        view.setScaleX(canvasScale);
        view.setScaleY(canvasScale);
        PointF initial = normalizeToGridAndBounds(
            view,
            alan.posX * density * canvasScale,
            alan.posY * density * canvasScale
        );
        view.setX(initial.x);
        view.setY(initial.y);
        view.setElevation(4 * density);
        view.setOnTouchListener(new AlanTouchHandler(alan, view));
        kanvasLayout.addView(view);
        view.post(() -> {
            PointF p = normalizeToGridAndBounds(view, view.getX(), view.getY());
            view.setX(p.x);
            view.setY(p.y);
        });
    }

    private void showPopup(OptikFormAlan alan, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Güncelle");
        popup.getMenu().add(0, 2, 1, "Klonla");
        popup.getMenu().add(0, 3, 2, "Sil");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Intent i = new Intent(this, YeniOptikFormAlanActivity.class);
                    i.putExtra(Constants.EXTRA_OPTIK_FORM_ID, formId);
                    i.putExtra(Constants.EXTRA_OPTIK_FORM_ALAN_ID, alan.id);
                    i.putExtra(Constants.EXTRA_IS_EDIT, true);
                    startActivityForResult(i, REQUEST_EDIT);
                    return true;
                case 2: klonla(alan); return true;
                case 3: sil(alan); return true;
            }
            return false;
        });
        popup.show();
    }

    private void kaydet() {
        executor.execute(() -> {
            for (int i = 0; i < kanvasLayout.getChildCount(); i++) {
                View v = kanvasLayout.getChildAt(i);
                if (v instanceof IsaretlemeAlanView) {
                    IsaretlemeAlanView iav = (IsaretlemeAlanView) v;
                    OptikFormAlan alan = iav.getAlan();
                    if (alan != null) {
                        PointF p = normalizeToGridAndBounds(v, v.getX(), v.getY());
                        alan.posX = p.x / density / canvasScale;
                        alan.posY = p.y / density / canvasScale;
                        db.optikFormAlanDao().update(alan);
                    }
                }
            }
            runOnUiThread(() -> Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show());
        });
    }

    private void exportPdf() {
        executor.execute(() -> {
            if (optikForm == null) return;
            List<OptikFormAlan> alanlar = db.optikFormAlanDao().getByFormId(formId);
            // Update positions from current view state
            for (int i = 0; i < kanvasLayout.getChildCount(); i++) {
                View v = kanvasLayout.getChildAt(i);
                if (v instanceof IsaretlemeAlanView) {
                    IsaretlemeAlanView iav = (IsaretlemeAlanView) v;
                    OptikFormAlan alan = iav.getAlan();
                    if (alan != null) {
                        PointF p = normalizeToGridAndBounds(v, v.getX(), v.getY());
                        for (OptikFormAlan a : alanlar) {
                            if (a.id == alan.id) {
                                a.posX = p.x / density / canvasScale;
                                a.posY = p.y / density / canvasScale;
                                break;
                            }
                        }
                    }
                }
            }
            Uri uri = PdfGenerator.generatePdf(this, optikForm, alanlar, exportTitle);
            runOnUiThread(() -> {
                if (uri != null) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/pdf");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "PDF Paylaş"));
                } else {
                    Toast.makeText(this, "PDF oluşturulamadı", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Kanvas görüntüsünü Resimler/TestPlus altına PNG olarak kaydeder. */
    private void exportToGallery() {
        if (optikForm == null) {
            Toast.makeText(this, "Form yükleniyor, bekleyin", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE_GALLERY);
                return;
            }
        }
        kanvasLayout.post(this::performGalleryExport);
    }

    private void performGalleryExport() {
        Bitmap bmp = bitmapFromView(kanvasLayout);
        bmp = addTitleToBitmap(bmp, exportTitle);
        if (bmp == null) {
            Toast.makeText(this, "Görüntü oluşturulamadı", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap finalBmp = bmp;
        executor.execute(() -> {
            boolean ok = saveImageToPictures(finalBmp);
            finalBmp.recycle();
            runOnUiThread(() -> Toast.makeText(this,
                ok ? "Galeriye kaydedildi (Resimler/TestPlus)"
                   : "Galeriye kaydedilemedi",
                Toast.LENGTH_LONG).show());
        });
    }

    private static Bitmap bitmapFromView(View v) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        v.draw(canvas);
        return bmp;
    }

    private boolean saveImageToPictures(Bitmap bitmap) {
        String baseName = optikForm != null && optikForm.ad != null && !optikForm.ad.isEmpty()
            ? optikForm.ad.replaceAll("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ]+", "_")
            : "OptikForm";
        String fileName = baseName + "_" + System.currentTimeMillis() + ".png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/TestPlus");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) return false;
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
        } catch (Exception e) {
            e.printStackTrace();
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {}
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        }
        return true;
    }

    private String resolveExportTitle() {
        if (optikForm == null) return "Sınav";
        try {
            Sinav sinav = db.sinavDao().getLatestByOptikFormId(optikForm.id);
            if (sinav != null && sinav.ad != null && !sinav.ad.trim().isEmpty()) {
                return sinav.ad.trim();
            }
        } catch (Exception ignored) {}
        if (optikForm.ad != null && !optikForm.ad.trim().isEmpty()) return optikForm.ad.trim();
        return "Sınav";
    }

    private Bitmap addTitleToBitmap(Bitmap src, String titleText) {
        if (src == null) return null;
        String title = (titleText != null && !titleText.trim().isEmpty()) ? titleText.trim() : "Sınav";
        int topPadding = Math.max(18, Math.round(src.getWidth() * 0.015f));
        int bottomPadding = Math.max(16, Math.round(src.getWidth() * 0.013f));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(36f, src.getWidth() * 0.036f));
        Paint.FontMetrics fm = paint.getFontMetrics();
        int titleHeight = Math.round((fm.descent - fm.ascent) + topPadding + bottomPadding);
        int outW = src.getWidth();
        int outH = src.getHeight() + titleHeight;

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);

        float baseline = topPadding - fm.ascent;
        float centerX = outW / 2f;
        c.drawText(title, centerX, baseline, paint);
        c.drawBitmap(src, 0, titleHeight, null);

        if (out != src) src.recycle();
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE_GALLERY) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                kanvasLayout.post(this::performGalleryExport);
            } else {
                Toast.makeText(this, "Depolama izni gerekli", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void klonla(OptikFormAlan alan) {
        executor.execute(() -> {
            OptikFormAlan klon = new OptikFormAlan();
            klon.formId = alan.formId; klon.tur = alan.tur; klon.yon = alan.yon;
            klon.etiket = alan.etiket; klon.desen = alan.desen; klon.ders = alan.ders;
            klon.blokSayisi = alan.blokSayisi; klon.bloktakiVeriSayisi = alan.bloktakiVeriSayisi;
            klon.ilkSoruNumarasi = alan.ilkSoruNumarasi; klon.blokArasiBosluk = alan.blokArasiBosluk;
            klon.posX = alan.posX + GRID_DP * 2; klon.posY = alan.posY + GRID_DP * 2;
            klon.siraNo = alan.siraNo + 1;
            db.optikFormAlanDao().insert(klon);
            runOnUiThread(this::yukleAlanlar);
        });
    }

    private void sil(OptikFormAlan alan) {
        executor.execute(() -> {
            db.optikFormAlanDao().deleteById(alan.id);
            runOnUiThread(this::yukleAlanlar);
        });
    }

    private int dpToPx(int dp) { return Math.round(dp * density); }

    private float getSafeInsetXPx() {
        if (optikForm == null) return 0f;
        int pdfW = PdfGenerator.getPdfWidth(optikForm);
        int pdfH = PdfGenerator.getPdfHeight(optikForm);
        int canvasWdp = PdfGenerator.getCanvasWidthDp(optikForm);
        float ptToDpX = canvasWdp / (float) pdfW;
        float insetPt = PdfGenerator.getMarkerPaddingPt(pdfW, pdfH) + PdfGenerator.MARKER_PT + 2f;
        return insetPt * ptToDpX * density * canvasScale;
    }

    private float getSafeInsetYPx() {
        if (optikForm == null) return 0f;
        int pdfW = PdfGenerator.getPdfWidth(optikForm);
        int pdfH = PdfGenerator.getPdfHeight(optikForm);
        int canvasHdp = PdfGenerator.getCanvasHeightDp(optikForm);
        float ptToDpY = canvasHdp / (float) pdfH;
        float markerInsetPt = PdfGenerator.getMarkerPaddingPt(pdfW, pdfH) + PdfGenerator.MARKER_PT + 2f;
        float topTitleInsetPt = PdfGenerator.getMarkerPaddingPt(pdfW, pdfH) + 16f;
        float insetPt = Math.max(markerInsetPt, topTitleInsetPt);
        return insetPt * ptToDpY * density * canvasScale;
    }

    private PointF clampToSafeBounds(View v, float x, float y) {
        float insetX = getSafeInsetXPx();
        float insetY = getSafeInsetYPx();
        float w = v.getWidth() * canvasScale;
        float h = v.getHeight() * canvasScale;

        float step = Math.max(1f, getGridStepPx());
        float originX = getGridOriginX();
        float originY = getGridOriginY();
        float minX = originX + (float) Math.ceil((insetX - originX) / step) * step;
        float minY = originY + (float) Math.ceil((insetY - originY) / step) * step;
        float maxX = canvasWidthPx - insetX - w;
        float maxY = canvasHeightPx - insetY - h;

        if (maxX < minX) {
            float centered = Math.max(0f, (canvasWidthPx - w) / 2f);
            minX = centered;
            maxX = centered;
        }
        if (maxY < minY) {
            float centered = Math.max(0f, (canvasHeightPx - h) / 2f);
            minY = centered;
            maxY = centered;
        }
        return new PointF(
            Math.max(minX, Math.min(x, maxX)),
            Math.max(minY, Math.min(y, maxY))
        );
    }

    private float snapToGrid(float valuePx) {
        // Grid hücreleri kanvas ölçeğine göre küçülür; snap o boyuta göre yapılmalı
        float gridPx = getGridStepPx();
        return Math.round(valuePx / gridPx) * gridPx;
    }

    private PointF snapToGridWithOrigin(float x, float y) {
        float originX = getGridOriginX();
        float originY = getGridOriginY();
        float sx = originX + snapToGrid(x - originX);
        float sy = originY + snapToGrid(y - originY);
        return new PointF(sx, sy);
    }

    private PointF normalizeToGridAndBounds(View v, float x, float y) {
        PointF clamped = clampToSafeBounds(v, x, y);
        PointF snapped = snapToGridWithOrigin(clamped.x, clamped.y);
        return clampToSafeBounds(v, snapped.x, snapped.y);
    }

    private float getGridStepPx() {
        return GRID_DP * density * fieldScale * canvasScale;
    }

    private float getGridOriginX() {
        float step = Math.max(1f, getGridStepPx());
        float rem = canvasWidthPx % step;
        return rem / 2f;
    }

    private float getGridOriginY() {
        float step = Math.max(1f, getGridStepPx());
        float rem = canvasHeightPx % step;
        return rem / 2f;
    }

    // ─── Alan Touch Handler (drag + long-press + tap) ─────────────────────────
    private class AlanTouchHandler implements View.OnTouchListener {
        private final OptikFormAlan alan;
        private final View view;
        private final GestureDetector gd;
        private float dX, dY;
        private boolean isDragging = false;
        private static final float DRAG_THRESHOLD_DP = 4f;

        AlanTouchHandler(OptikFormAlan alan, View view) {
            this.alan = alan;
            this.view = view;
            this.gd = new GestureDetector(OptikFormKanvasActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {

                @Override
                public void onLongPress(MotionEvent e) {
                    if (!isDragging) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showPopup(alan, view);
                    }
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (!isDragging) {
                        Intent i = new Intent(OptikFormKanvasActivity.this, YeniOptikFormAlanActivity.class);
                        i.putExtra(Constants.EXTRA_OPTIK_FORM_ID, formId);
                        i.putExtra(Constants.EXTRA_OPTIK_FORM_ALAN_ID, alan.id);
                        i.putExtra(Constants.EXTRA_IS_EDIT, true);
                        startActivityForResult(i, REQUEST_EDIT);
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            gd.onTouchEvent(event);
            float threshold = DRAG_THRESHOLD_DP * density;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    isDragging = false;
                    v.bringToFront();
                    // Parent ScrollView/HorizontalScrollView olayı çalmasın
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    if (!isDragging) {
                        if (Math.abs(newX - v.getX()) > threshold || Math.abs(newY - v.getY()) > threshold) {
                            isDragging = true;
                            // GestureDetector'a iptal sinyali gönder, uzun basış tetiklenmesin
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            gd.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                    }
                    if (isDragging) {
                        // Güvenli baskı alanı içinde tut (kenara yaslanıp bozulma olmasın).
                        PointF p = clampToSafeBounds(v, newX, newY);
                        v.setX(p.x);
                        v.setY(p.y);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (isDragging) {
                        PointF p = normalizeToGridAndBounds(v, v.getX(), v.getY());
                        v.animate().x(p.x).y(p.y).setDuration(100).start();
                        alan.posX = p.x / density / canvasScale;
                        alan.posY = p.y / density / canvasScale;
                        isDragging = false;
                    }
                    return true;
            }
            return false;
        }
    }

    // ─── Grid Drawable ────────────────────────────────────────────────────────
    private static class GridDrawable extends Drawable {
        private final Paint paintBg = new Paint();
        private final Paint paintGrid = new Paint();
        private final Paint paintBorder = new Paint();
        private final Paint paintSafe = new Paint();
        private final Paint paintMarker = new Paint();
        private final float gridSize;
        private final float originX;
        private final float originY;
        private final float insetX;
        private final float insetY;
        private final float markerSize;

        GridDrawable(float gridSize, float originX, float originY,
                     float insetX, float insetY, float markerSize) {
            this.gridSize = gridSize;
            this.originX = originX;
            this.originY = originY;
            this.insetX = insetX;
            this.insetY = insetY;
            this.markerSize = markerSize;
            paintBg.setColor(Color.WHITE);
            paintGrid.setColor(0xFFEEEEEE);
            paintGrid.setStrokeWidth(1f);
            paintBorder.setColor(0xFF9E9E9E);
            paintBorder.setStyle(Paint.Style.STROKE);
            paintBorder.setStrokeWidth(2f);
            paintSafe.setColor(0x4481C784);
            paintSafe.setStyle(Paint.Style.STROKE);
            paintSafe.setStrokeWidth(1f);
            paintMarker.setColor(0xFF424242);
            paintMarker.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, paintBg);
            for (float x = originX; x <= b.width(); x += gridSize)
                canvas.drawLine(x, 0, x, b.height(), paintGrid);
            for (float y = originY; y <= b.height(); y += gridSize)
                canvas.drawLine(0, y, b.width(), y, paintGrid);
            canvas.drawRect(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1, paintBorder);
            canvas.drawRect(insetX, insetY, b.width() - insetX, b.height() - insetY, paintSafe);

            // Köşe markerları (görsel referans): güvenli alanın dışında köşelerde gösterilir.
            float m = markerSize;
            float p = Math.max(2f, insetX - m - 2f);
            float q = Math.max(2f, insetY - m - 2f);
            canvas.drawRect(p, q, p + m, q + m, paintMarker);
            canvas.drawRect(b.width() - p - m, q, b.width() - p, q + m, paintMarker);
            canvas.drawRect(p, b.height() - q - m, p + m, b.height() - q, paintMarker);
            canvas.drawRect(b.width() - p - m, b.height() - q - m, b.width() - p, b.height() - q, paintMarker);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
