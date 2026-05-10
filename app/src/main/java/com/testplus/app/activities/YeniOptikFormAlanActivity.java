package com.testplus.app.activities;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.OptikForm;
import com.testplus.app.database.entities.OptikFormAlan;
import com.testplus.app.utils.Constants;
import com.testplus.app.views.IsaretlemeAlanView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YeniOptikFormAlanActivity extends AppCompatActivity {
    private Spinner spinnerTur, spinnerYon, spinnerDesen, spinnerDers;
    private EditText etEtiket, etBlokSayisi, etBloktakiVeriSayisi;
    private EditText etVeriSayisiStandalone, etIlkSoruNo;
    private View layoutCevaplarExtra;
    private View layoutVeriSayisiStandalone;
    private IsaretlemeAlanView onIzlemeView;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private long formId;
    private long editAlanId = -1;
    private boolean isEdit = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yeni_optik_form_alan);
        db = AppDatabase.getInstance(this);
        formId = getIntent().getLongExtra(Constants.EXTRA_OPTIK_FORM_ID, -1);
        isEdit = getIntent().getBooleanExtra(Constants.EXTRA_IS_EDIT, false);
        editAlanId = getIntent().getLongExtra(Constants.EXTRA_OPTIK_FORM_ALAN_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("Yeni Optik Form Alan");
        toolbar.setNavigationIcon(R.drawable.ic_close);
        toolbar.setNavigationOnClickListener(v -> finish());

        initViews();
        applyPreviewPaperScale();
        setupSpinners();
        setupPreviewUpdate();

        TextView tvTamam = findViewById(R.id.tvTamam);
        tvTamam.setOnClickListener(v -> kaydet());

        if (isEdit && editAlanId != -1) {
            yukleAlan();
        }
    }

    private void applyPreviewPaperScale() {
        executor.execute(() -> {
            OptikForm form = db.optikFormDao().getById(formId);
            if (form == null) return;
            float fs = com.testplus.app.utils.PdfGenerator.getFieldScaleForPage(
                com.testplus.app.utils.PdfGenerator.getPdfWidth(form),
                com.testplus.app.utils.PdfGenerator.getPdfHeight(form));
            runOnUiThread(() -> onIzlemeView.setFieldScale(fs));
        });
    }

    private void initViews() {
        spinnerTur = findViewById(R.id.spinnerTur);
        spinnerYon = findViewById(R.id.spinnerYon);
        spinnerDesen = findViewById(R.id.spinnerDesen);
        spinnerDers = findViewById(R.id.spinnerDers);
        etEtiket = findViewById(R.id.etEtiket);
        etBlokSayisi = findViewById(R.id.etBlokSayisi);
        etBloktakiVeriSayisi = findViewById(R.id.etBloktakiVeriSayisi);
        etVeriSayisiStandalone = findViewById(R.id.etVeriSayisiStandalone);
        etIlkSoruNo = findViewById(R.id.etIlkSoruNo);
        layoutCevaplarExtra = findViewById(R.id.layoutCevaplarExtra);
        layoutVeriSayisiStandalone = findViewById(R.id.layoutVeriSayisiStandalone);
        onIzlemeView = findViewById(R.id.onIzlemeView);
    }

    private void setupSpinners() {
        String[] turler = {"Cevaplar", "Kitapçık", "Ad Soyad", "Sınıf", "Numara"};
        spinnerTur.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, turler));

        String[] yonler = {Constants.YON_YATAY, Constants.YON_DIKEY};
        spinnerYon.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, yonler));

        spinnerDers.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Constants.DERSLER));

        spinnerTur.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isCevaplar = (position == 0);
                layoutCevaplarExtra.setVisibility(isCevaplar ? View.VISIBLE : View.GONE);
                layoutVeriSayisiStandalone.setVisibility(isCevaplar ? View.GONE : View.VISIBLE);
                etBlokSayisi.setEnabled(isCevaplar);
                if (!isCevaplar) {
                    String[] labels = {"Cevaplar", "Kitapçık", "Ad Soyad", "Sınıf", "Numara"};
                    etEtiket.setText(labels[position]);
                    etBlokSayisi.setText("1");
                }
                bindDesenSpinnerForTur(position, null);
                updatePreview();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        bindDesenSpinnerForTur(0, null);
    }

    /** Cevaplar/Kitapçık: AB–ABCDE; Ad Soyad: harf seti; Sınıf: tüm desenler. */
    private void bindDesenSpinnerForTur(int turPosition, String selectDesen) {
        String[] items;
        if (turPosition == 0 || turPosition == 1) {
            items = Constants.DESEN_CEVAP_KITAPCIK;
        } else if (turPosition == 2) {
            items = new String[]{Constants.DESEN_AD_SOYAD};
        } else if (turPosition == 4) {
            items = new String[]{Constants.DESEN_NUMARA};
        } else {
            items = Constants.DESENLER;
        }
        spinnerDesen.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        String want = selectDesen;
        if (want == null && spinnerDesen.getAdapter() != null && spinnerDesen.getAdapter().getCount() > 0) {
            want = spinnerDesen.getAdapter().getItem(0).toString();
        }
        if (want != null) {
            for (int i = 0; i < items.length; i++) {
                if (items[i].equals(want)) {
                    spinnerDesen.setSelection(i);
                    return;
                }
            }
        }
        spinnerDesen.setSelection(0);
    }

    private void setupPreviewUpdate() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        etEtiket.addTextChangedListener(watcher);
        etBloktakiVeriSayisi.addTextChangedListener(watcher);
        etVeriSayisiStandalone.addTextChangedListener(watcher);
        etBlokSayisi.addTextChangedListener(watcher);
        spinnerDesen.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updatePreview(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        spinnerYon.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updatePreview(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void updatePreview() {
        OptikFormAlan alan = buildAlan();
        onIzlemeView.setAlan(alan);
    }

    private int getVeriSayisi() {
        boolean isCevaplar = spinnerTur.getSelectedItemPosition() == 0;
        EditText et = isCevaplar ? etBloktakiVeriSayisi : etVeriSayisiStandalone;
        try { return Integer.parseInt(et.getText().toString()); } catch (Exception e) { return 0; }
    }

    private OptikFormAlan buildAlan() {
        OptikFormAlan alan = new OptikFormAlan();
        String[] turKeys = {
            Constants.TUR_CEVAPLAR, Constants.TUR_KITAPCIK, Constants.TUR_AD_SOYAD,
            Constants.TUR_SINIF, Constants.TUR_NUMARA
        };
        alan.tur = turKeys[spinnerTur.getSelectedItemPosition()];
        alan.yon = Constants.normalizeYon(spinnerYon.getSelectedItem().toString());
        alan.etiket = etEtiket.getText().toString();
        alan.desen = spinnerDesen.getSelectedItem().toString();
        alan.ders = spinnerDers.getSelectedItem() != null ? spinnerDers.getSelectedItem().toString() : "";
        if (Constants.TUR_CEVAPLAR.equals(alan.tur)) {
            try { alan.blokSayisi = Math.max(1, Integer.parseInt(etBlokSayisi.getText().toString())); }
            catch (Exception e) { alan.blokSayisi = 1; }
        } else {
            alan.blokSayisi = 1;
        }
        alan.bloktakiVeriSayisi = getVeriSayisi();
        try { alan.ilkSoruNumarasi = Integer.parseInt(etIlkSoruNo.getText().toString()); } catch (Exception e) { alan.ilkSoruNumarasi = 1; }
        alan.blokArasiBosluk = false;
        return alan;
    }

    private void kaydet() {
        boolean isCevaplar = spinnerTur.getSelectedItemPosition() == 0;
        if (getVeriSayisi() <= 0) {
            EditText et = isCevaplar ? etBloktakiVeriSayisi : etVeriSayisiStandalone;
            et.setError("Zorunludur");
            return;
        }
        if (isCevaplar) {
            String blok = etBlokSayisi.getText().toString().trim();
            if (blok.isEmpty()) { etBlokSayisi.setError("Zorunludur"); return; }
            try {
                if (Integer.parseInt(blok) <= 0) {
                    etBlokSayisi.setError("1 veya daha büyük olmalı");
                    return;
                }
            } catch (Exception e) {
                etBlokSayisi.setError("Geçerli sayı girin");
                return;
            }
        }
        OptikFormAlan alan = buildAlan();
        alan.formId = formId;
        alan.siraNo = (int) System.currentTimeMillis();

        executor.execute(() -> {
            if (isEdit && editAlanId != -1) {
                alan.id = editAlanId;
                db.optikFormAlanDao().update(alan);
            } else {
                db.optikFormAlanDao().insert(alan);
            }
            runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
        });
    }

    private void yukleAlan() {
        executor.execute(() -> {
            OptikFormAlan alan = db.optikFormAlanDao().getById(editAlanId);
            if (alan == null) return;
            runOnUiThread(() -> {
                String[] turKeys = {
                    Constants.TUR_CEVAPLAR, Constants.TUR_KITAPCIK, Constants.TUR_AD_SOYAD,
                    Constants.TUR_SINIF, Constants.TUR_NUMARA
                };
                int turIdx = 0;
                for (int i = 0; i < turKeys.length; i++) {
                    if (turKeys[i].equals(alan.tur)) {
                        turIdx = i;
                        spinnerTur.setSelection(i);
                        break;
                    }
                }
                String[] yonler = {Constants.YON_YATAY, Constants.YON_DIKEY};
                for (int i = 0; i < yonler.length; i++) {
                    if (yonler[i].equals(Constants.normalizeYon(alan.yon))) {
                        spinnerYon.setSelection(i);
                    }
                }
                etEtiket.setText(alan.etiket);
                bindDesenSpinnerForTur(turIdx, alan.desen);
                etBlokSayisi.setText(alan.blokSayisi > 0 ? String.valueOf(alan.blokSayisi) : "");
                String veriStr = alan.bloktakiVeriSayisi > 0 ? String.valueOf(alan.bloktakiVeriSayisi) : "";
                etBloktakiVeriSayisi.setText(veriStr);
                etVeriSayisiStandalone.setText(veriStr);
                if (etIlkSoruNo != null) etIlkSoruNo.setText(alan.ilkSoruNumarasi > 0 ? String.valueOf(alan.ilkSoruNumarasi) : "1");
                if (alan.ders != null) {
                    for (int i = 0; i < Constants.DERSLER.length; i++) {
                        if (Constants.DERSLER[i].equals(alan.ders)) { spinnerDers.setSelection(i); break; }
                    }
                }
                updatePreview();
            });
        });
    }
}
