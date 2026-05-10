package com.testplus.app.activities;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.CevapAnahtari;
import com.testplus.app.database.entities.OptikFormAlan;
import com.testplus.app.utils.Constants;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CevapAnahtariActivity extends AppCompatActivity {
    private LinearLayout containerLayout;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private long sinavId, alanId;
    private OptikFormAlan alan;
    private List<String> cevaplar = new ArrayList<>();
    private List<String> secilenler = new ArrayList<>();
    private Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cevap_anahtari);
        db = AppDatabase.getInstance(this);
        sinavId = getIntent().getLongExtra(Constants.EXTRA_SINAV_ID, -1);
        alanId = getIntent().getLongExtra(Constants.EXTRA_OPTIK_FORM_ALAN_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        containerLayout = findViewById(R.id.containerLayout);

        TextView tvKaydet = findViewById(R.id.tvKaydet);
        tvKaydet.setOnClickListener(v -> kaydet());

        yukleAlan();
    }

    private void yukleAlan() {
        executor.execute(() -> {
            alan = db.optikFormAlanDao().getById(alanId);
            if (alan == null) return;
            CevapAnahtari mevcut = db.cevapAnahtariDao().getBySinavAndAlan(sinavId, alanId);
            List<String> mevcutCevaplar = null;
            if (mevcut != null && mevcut.cevaplarJson != null) {
                Type type = new TypeToken<List<String>>(){}.getType();
                mevcutCevaplar = gson.fromJson(mevcut.cevaplarJson, type);
            }
            final List<String> finalMevcut = mevcutCevaplar;
            runOnUiThread(() -> {
                getSupportActionBar().setTitle(alan.ders != null && !alan.ders.isEmpty() ? alan.ders : alan.etiket);
                buildGrid(alan, finalMevcut);
            });
        });
    }

    private void buildGrid(OptikFormAlan alan, List<String> mevcutCevaplar) {
        containerLayout.removeAllViews();
        String desen = alan.desen != null ? alan.desen : "ABCD";
        char[] secenekler = desen.toCharArray();
        int toplamSoru = alan.blokSayisi * alan.bloktakiVeriSayisi;
        if (toplamSoru <= 0) toplamSoru = 20;

        secilenler.clear();
        for (int i = 0; i < toplamSoru; i++) {
            secilenler.add(mevcutCevaplar != null && i < mevcutCevaplar.size() ? mevcutCevaplar.get(i) : "");
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout soruContainer = new LinearLayout(this);
        soruContainer.setOrientation(LinearLayout.VERTICAL);
        soruContainer.setPadding(16, 16, 16, 16);
        scrollView.addView(soruContainer);
        containerLayout.addView(scrollView);

        for (int i = 0; i < toplamSoru; i++) {
            final int soruIndex = i;
            LinearLayout satir = new LinearLayout(this);
            satir.setOrientation(LinearLayout.HORIZONTAL);
            satir.setPadding(8, 8, 8, 8);
            satir.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvNo = new TextView(this);
            tvNo.setText((i + alan.ilkSoruNumarasi) + ")");
            tvNo.setTextSize(14);
            tvNo.setMinWidth(60);
            tvNo.setPadding(0, 0, 8, 0);
            satir.addView(tvNo);

            int btnPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44, getResources().getDisplayMetrics());
            List<Button> butonlar = new ArrayList<>();
            for (char c : secenekler) {
                Button btn = new Button(this);
                btn.setText(String.valueOf(c));
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setIncludeFontPadding(false);
                btn.setAllCaps(false);
                btn.setMinWidth(0);
                btn.setMinHeight(0);
                btn.setPadding(0, 0, 0, 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnPx, btnPx);
                lp.setMargins(4, 0, 4, 0);
                btn.setLayoutParams(lp);
                String cevap = String.valueOf(c);
                boolean secili = cevap.equals(secilenler.get(soruIndex));
                btn.setBackgroundResource(secili ? R.drawable.btn_cevap_secili : R.drawable.btn_cevap);
                btn.setTextColor(secili ? 0xFFFFFFFF : 0xFF212121);

                btn.setOnClickListener(v -> {
                    secilenler.set(soruIndex, cevap.equals(secilenler.get(soruIndex)) ? "" : cevap);
                    for (Button b : butonlar) {
                        String bc = b.getText().toString();
                        boolean s = bc.equals(secilenler.get(soruIndex));
                        b.setBackgroundResource(s ? R.drawable.btn_cevap_secili : R.drawable.btn_cevap);
                        b.setTextColor(s ? 0xFFFFFFFF : 0xFF212121);
                    }
                });
                butonlar.add(btn);
                satir.addView(btn);
            }
            soruContainer.addView(satir);
            // Divider
            View divider = new View(this);
            divider.setBackgroundColor(0xFFEEEEEE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divider.setLayoutParams(dlp);
            soruContainer.addView(divider);
        }
    }

    private void kaydet() {
        executor.execute(() -> {
            db.cevapAnahtariDao().deleteBySinavAndAlan(sinavId, alanId);
            CevapAnahtari anahtar = new CevapAnahtari();
            anahtar.sinavId = sinavId;
            anahtar.optikFormAlanId = alanId;
            anahtar.ders = alan != null ? alan.ders : "";
            anahtar.cevaplarJson = gson.toJson(secilenler);
            db.cevapAnahtariDao().insert(anahtar);
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        });
    }
}
