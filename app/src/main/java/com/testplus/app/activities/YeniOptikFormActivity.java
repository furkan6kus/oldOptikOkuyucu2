package com.testplus.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.OptikForm;
import com.testplus.app.utils.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YeniOptikFormActivity extends AppCompatActivity {
    private static final int REQ_ALAN = 4401;

    private EditText etAd;
    private Spinner spinnerKagit, spinnerYon;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private long editId = -1;
    private boolean isEdit = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yeni_optik_form);
        db = AppDatabase.getInstance(this);
        isEdit = getIntent().getBooleanExtra(Constants.EXTRA_IS_EDIT, false);
        editId = getIntent().getLongExtra(Constants.EXTRA_OPTIK_FORM_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationIcon(R.drawable.ic_close);
        toolbar.setNavigationOnClickListener(v -> finish());

        if (isEdit) {
            getSupportActionBar().setTitle("Optik Formu Düzenle");
        } else {
            getSupportActionBar().setTitle("Yeni Optik Form");
        }

        etAd = findViewById(R.id.etAd);
        spinnerKagit = findViewById(R.id.spinnerKagit);
        spinnerYon = findViewById(R.id.spinnerYon);

        String[] kagitlar = {Constants.KAGIT_A4, Constants.KAGIT_A5, Constants.KAGIT_A6};
        String[] yonler = {Constants.YON_DIKEY, Constants.YON_YATAY};

        spinnerKagit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, kagitlar));
        spinnerYon.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, yonler));

        TextView tvKaydet = findViewById(R.id.tvKaydet);
        tvKaydet.setOnClickListener(v -> kaydet());

        ImageButton btnAlanEkle = findViewById(R.id.btnAlanEkle);
        btnAlanEkle.setOnClickListener(v -> gosterAlanEkleSheet());

        if (isEdit && editId != -1) {
            yukleForm();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALAN && resultCode == RESULT_OK) {
            Toast.makeText(this, "Alan kaydedildi", Toast.LENGTH_SHORT).show();
        }
    }

    private void gosterAlanEkleSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_optik_alan_ekle, null);
        sheet.setContentView(content);
        content.findViewById(R.id.rowOptikTurEkle).setOnClickListener(v -> {
            sheet.dismiss();
            acOptikFormAlanEkleme();
        });
        sheet.show();
    }

    private void acOptikFormAlanEkleme() {
        String ad = etAd.getText().toString().trim();
        if (ad.isEmpty()) {
            etAd.setError("Önce optik adını girin");
            return;
        }
        String kagit = spinnerKagit.getSelectedItem().toString();
        String yon = Constants.normalizeYon(spinnerYon.getSelectedItem().toString());

        if (editId != -1) {
            startAlanActivity(editId);
            return;
        }

        executor.execute(() -> {
            OptikForm form = new OptikForm();
            form.ad = ad;
            form.kagit = kagit;
            form.yon = yon;
            long id = db.optikFormDao().insert(form);
            runOnUiThread(() -> {
                editId = id;
                isEdit = true;
                getSupportActionBar().setTitle("Optik Formu Düzenle");
                startAlanActivity(id);
            });
        });
    }

    private void startAlanActivity(long formId) {
        Intent i = new Intent(this, YeniOptikFormAlanActivity.class);
        i.putExtra(Constants.EXTRA_OPTIK_FORM_ID, formId);
        i.putExtra(Constants.EXTRA_IS_EDIT, false);
        startActivityForResult(i, REQ_ALAN);
    }

    private void yukleForm() {
        executor.execute(() -> {
            OptikForm form = db.optikFormDao().getById(editId);
            if (form != null) {
                runOnUiThread(() -> {
                    etAd.setText(form.ad);
                    String[] kagitlar = {Constants.KAGIT_A4, Constants.KAGIT_A5, Constants.KAGIT_A6};
                    for (int i = 0; i < kagitlar.length; i++) {
                        if (kagitlar[i].equals(form.kagit)) spinnerKagit.setSelection(i);
                    }
                    String[] yonler = {Constants.YON_DIKEY, Constants.YON_YATAY};
                    for (int i = 0; i < yonler.length; i++) {
                        if (yonler[i].equals(Constants.normalizeYon(form.yon))) {
                            spinnerYon.setSelection(i);
                        }
                    }
                });
            }
        });
    }

    private void kaydet() {
        String ad = etAd.getText().toString().trim();
        if (ad.isEmpty()) {
            etAd.setError("Ad zorunludur");
            return;
        }
        String kagit = spinnerKagit.getSelectedItem().toString();
        String yon = Constants.normalizeYon(spinnerYon.getSelectedItem().toString());

        executor.execute(() -> {
            if (editId != -1) {
                OptikForm form = db.optikFormDao().getById(editId);
                if (form != null) {
                    form.ad = ad;
                    form.kagit = kagit;
                    form.yon = yon;
                    db.optikFormDao().update(form);
                }
            } else {
                OptikForm form = new OptikForm();
                form.ad = ad;
                form.kagit = kagit;
                form.yon = yon;
                db.optikFormDao().insert(form);
            }
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        });
    }
}
