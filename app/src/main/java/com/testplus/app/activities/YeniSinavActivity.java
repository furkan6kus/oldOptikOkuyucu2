package com.testplus.app.activities;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.OptikForm;
import com.testplus.app.database.entities.Sinav;
import com.testplus.app.utils.Constants;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YeniSinavActivity extends AppCompatActivity {
    private EditText etAd;
    private Spinner spinnerOptikForm, spinnerCeza;
    private Button btnTarih;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private List<OptikForm> optikFormlar = new ArrayList<>();
    private Calendar takvim = Calendar.getInstance();
    private long editId = -1;
    private boolean isEdit = false;
    private SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yeni_sinav);
        db = AppDatabase.getInstance(this);
        isEdit = getIntent().getBooleanExtra(Constants.EXTRA_IS_EDIT, false);
        editId = getIntent().getLongExtra(Constants.EXTRA_SINAV_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_close);
        toolbar.setNavigationOnClickListener(v -> finish());
        getSupportActionBar().setTitle(isEdit ? "Sınavı Düzenle" : "Yeni Sınav");

        etAd = findViewById(R.id.etAd);
        spinnerOptikForm = findViewById(R.id.spinnerOptikForm);
        spinnerCeza = findViewById(R.id.spinnerCeza);
        btnTarih = findViewById(R.id.btnTarih);

        String[] cezalar = {
            "Olduğu gibi bırak",
            "1 yanlış 1 doğruyu götürsün",
            "2 yanlış 1 doğruyu götürsün",
            "3 yanlış 1 doğruyu götürsün",
            "4 yanlış 1 doğruyu götürsün"
        };
        spinnerCeza.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cezalar));

        btnTarih.setText(sdf.format(takvim.getTime()));
        btnTarih.setOnClickListener(v -> {
            new DatePickerDialog(this, (view, year, month, day) -> {
                takvim.set(year, month, day);
                btnTarih.setText(sdf.format(takvim.getTime()));
            }, takvim.get(Calendar.YEAR), takvim.get(Calendar.MONTH), takvim.get(Calendar.DAY_OF_MONTH)).show();
        });

        TextView tvKaydet = findViewById(R.id.tvKaydet);
        tvKaydet.setOnClickListener(v -> kaydet());

        yukleOptikFormlar();
    }

    private boolean formlarYuklendi = false;

    private void yukleOptikFormlar() {
        db.optikFormDao().getAll().observe(this, forms -> {
            optikFormlar.clear();
            optikFormlar.addAll(forms);
            List<String> isimler = new ArrayList<>();
            for (OptikForm f : optikFormlar) isimler.add(f.ad);
            spinnerOptikForm.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, isimler));
            if (!formlarYuklendi && isEdit && editId != -1) {
                formlarYuklendi = true;
                yukleForm();
            }
        });
    }

    private void yukleForm() {
        executor.execute(() -> {
            Sinav sinav = db.sinavDao().getById(editId);
            if (sinav == null) return;
            runOnUiThread(() -> {
                etAd.setText(sinav.ad);
                takvim.setTimeInMillis(sinav.sinavTarihi > 0 ? sinav.sinavTarihi : System.currentTimeMillis());
                btnTarih.setText(sdf.format(takvim.getTime()));
                for (int i = 0; i < optikFormlar.size(); i++) {
                    if (optikFormlar.get(i).id == sinav.optikFormId) {
                        spinnerOptikForm.setSelection(i);
                        break;
                    }
                }
                String[] cezaKeys = {Constants.CEZA_YOK, Constants.CEZA_BIR_BIR, Constants.CEZA_IKI_BIR, Constants.CEZA_UC_BIR, Constants.CEZA_DORT_BIR};
                for (int i = 0; i < cezaKeys.length; i++) {
                    if (cezaKeys[i].equals(sinav.yanlisCezasi)) {
                        spinnerCeza.setSelection(i);
                        break;
                    }
                }
            });
        });
    }

    private void kaydet() {
        String ad = etAd.getText().toString().trim();
        if (ad.isEmpty()) { etAd.setError("Zorunludur"); return; }
        if (optikFormlar.isEmpty()) { Toast.makeText(this, "Lütfen önce bir optik form oluşturun", Toast.LENGTH_SHORT).show(); return; }

        String[] cezaKeys = {Constants.CEZA_YOK, Constants.CEZA_BIR_BIR, Constants.CEZA_IKI_BIR, Constants.CEZA_UC_BIR, Constants.CEZA_DORT_BIR};
        String ceza = cezaKeys[spinnerCeza.getSelectedItemPosition()];
        long optikFormId = optikFormlar.get(spinnerOptikForm.getSelectedItemPosition()).id;

        executor.execute(() -> {
            if (isEdit && editId != -1) {
                Sinav sinav = db.sinavDao().getById(editId);
                if (sinav != null) {
                    sinav.ad = ad;
                    sinav.optikFormId = optikFormId;
                    sinav.yanlisCezasi = ceza;
                    sinav.sinavTarihi = takvim.getTimeInMillis();
                    db.sinavDao().update(sinav);
                }
            } else {
                Sinav sinav = new Sinav();
                sinav.ad = ad;
                sinav.optikFormId = optikFormId;
                sinav.yanlisCezasi = ceza;
                sinav.sinavTarihi = takvim.getTimeInMillis();
                db.sinavDao().insert(sinav);
            }
            runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
        });
    }
}
