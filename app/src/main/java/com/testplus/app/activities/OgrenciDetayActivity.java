package com.testplus.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.*;
import com.testplus.app.utils.Constants;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OgrenciDetayActivity extends AppCompatActivity {
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private long kagitId, sinavId;
    private OgrenciKagidi kagit;
    private Sinav sinav;
    private List<OptikFormAlan> cevapAlanlar = new ArrayList<>();
    private Gson gson = new Gson();
    private Spinner spinnerDers;
    private LinearLayout cevaplarLayout;
    private TextView tvToplam, tvPybs, tvDogru, tvYanlis, tvBos, tvNet;
    private EditText etAd, etNumara, etSinif, etKitapcik;
    private TextInputLayout tilAd, tilNumara, tilSinif, tilKitapcik;
    private View layoutToplamNetRow, layoutDersSecimi, layoutCevapScroll, layoutDybNet;
    private View layoutNumaraSinifRow;

    private boolean formHasAdSoyad, formHasNumara, formHasSinif, formHasKitapcik, formHasCevap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ogrenci_detay);
        db = AppDatabase.getInstance(this);
        kagitId = getIntent().getLongExtra(Constants.EXTRA_OGRENCI_KAGIDI_ID, -1);
        sinavId = getIntent().getLongExtra(Constants.EXTRA_SINAV_ID, -1);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_ogrenci_detay);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_kaydet) {
                kaydetBilgiler();
                return true;
            }
            return false;
        });

        spinnerDers = findViewById(R.id.spinnerDers);
        cevaplarLayout = findViewById(R.id.cevaplarLayout);
        tvToplam = findViewById(R.id.tvToplam);
        tvPybs = findViewById(R.id.tvPybs);
        tvDogru = findViewById(R.id.tvDogru);
        tvYanlis = findViewById(R.id.tvYanlis);
        tvBos = findViewById(R.id.tvBos);
        tvNet = findViewById(R.id.tvNet);
        etAd = findViewById(R.id.etAd);
        etNumara = findViewById(R.id.etNumara);
        etSinif = findViewById(R.id.etSinif);
        etKitapcik = findViewById(R.id.etKitapcik);
        tilAd = findViewById(R.id.tilAd);
        tilNumara = findViewById(R.id.tilNumara);
        tilSinif = findViewById(R.id.tilSinif);
        tilKitapcik = findViewById(R.id.tilKitapcik);
        layoutToplamNetRow = findViewById(R.id.layoutToplamNetRow);
        layoutDersSecimi = findViewById(R.id.layoutDersSecimi);
        layoutCevapScroll = findViewById(R.id.layoutCevapScroll);
        layoutDybNet = findViewById(R.id.layoutDybNet);
        layoutNumaraSinifRow = findViewById(R.id.layoutNumaraSinifRow);

        yukleDetay();
    }

    private void yukleDetay() {
        executor.execute(() -> {
            kagit = db.ogrenciKagidiDao().getById(kagitId);
            if (kagit == null) return;
            sinav = db.sinavDao().getById(sinavId);
            cevapAlanlar.clear();
            formHasAdSoyad = formHasNumara = formHasSinif = formHasKitapcik = false;
            if (sinav != null) {
                List<OptikFormAlan> alanlar = db.optikFormAlanDao().getByFormId(sinav.optikFormId);
                for (OptikFormAlan alan : alanlar) {
                    if (Constants.TUR_CEVAPLAR.equals(alan.tur)) {
                        cevapAlanlar.add(alan);
                    } else if (Constants.TUR_AD_SOYAD.equals(alan.tur)) {
                        formHasAdSoyad = true;
                    } else if (Constants.TUR_NUMARA.equals(alan.tur)) {
                        formHasNumara = true;
                    } else if (Constants.TUR_SINIF.equals(alan.tur)) {
                        formHasSinif = true;
                    } else if (Constants.TUR_KITAPCIK.equals(alan.tur)) {
                        formHasKitapcik = true;
                    }
                }
            }
            formHasCevap = !cevapAlanlar.isEmpty();

            runOnUiThread(() -> {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(kagit.ad != null ? kagit.ad : "");
                }
                applyOptikFormUiVisibility();
                kagitAlanlariniYansit();
                hesaplaToplam();
                setupDersSpinner();
            });
        });
    }

    /** Kayıtlı kağıt verisini ekrana yazar (yalnızca görünür alanlar). */
    private void kagitAlanlariniYansit() {
        if (tilAd != null && tilAd.getVisibility() == View.VISIBLE && etAd != null) {
            etAd.setText(kagit.ad != null ? kagit.ad : "");
        }
        if (tilNumara != null && tilNumara.getVisibility() == View.VISIBLE && etNumara != null) {
            etNumara.setText(kagit.numara != null ? kagit.numara : "");
        }
        if (tilSinif != null && tilSinif.getVisibility() == View.VISIBLE && etSinif != null) {
            etSinif.setText(kagit.sinif != null ? kagit.sinif : "");
        }
        if (tilKitapcik != null && tilKitapcik.getVisibility() == View.VISIBLE && etKitapcik != null) {
            etKitapcik.setText(kagit.kitapcik != null ? kagit.kitapcik : "");
        }
    }

    private void applyOptikFormUiVisibility() {
        if (tilAd != null) {
            tilAd.setVisibility(formHasAdSoyad ? View.VISIBLE : View.GONE);
            tilAd.setHint(formHasAdSoyad ? "Ad Soyad *" : "Ad Soyad");
        }
        if (tilNumara != null) tilNumara.setVisibility(formHasNumara ? View.VISIBLE : View.GONE);
        if (tilSinif != null) tilSinif.setVisibility(formHasSinif ? View.VISIBLE : View.GONE);
        if (tilKitapcik != null) tilKitapcik.setVisibility(formHasKitapcik ? View.VISIBLE : View.GONE);
        if (layoutNumaraSinifRow != null) {
            layoutNumaraSinifRow.setVisibility((formHasNumara || formHasSinif) ? View.VISIBLE : View.GONE);
        }
        if (layoutToplamNetRow != null) {
            layoutToplamNetRow.setVisibility(formHasCevap ? View.VISIBLE : View.GONE);
        }
        if (layoutDersSecimi != null) {
            layoutDersSecimi.setVisibility(formHasCevap ? View.VISIBLE : View.GONE);
        }
        if (layoutCevapScroll != null) {
            layoutCevapScroll.setVisibility(formHasCevap ? View.VISIBLE : View.GONE);
        }
        if (layoutDybNet != null) {
            layoutDybNet.setVisibility(formHasCevap ? View.VISIBLE : View.GONE);
        }
    }

    private void kaydetBilgiler() {
        if (kagit == null) return;

        String ad = tilAd != null && tilAd.getVisibility() == View.VISIBLE && etAd != null
            ? etAd.getText().toString().trim() : "";
        String numara = tilNumara != null && tilNumara.getVisibility() == View.VISIBLE && etNumara != null
            ? etNumara.getText().toString().trim() : "";
        String sinifStr = tilSinif != null && tilSinif.getVisibility() == View.VISIBLE && etSinif != null
            ? etSinif.getText().toString().trim() : "";
        String kitapcikStr = tilKitapcik != null && tilKitapcik.getVisibility() == View.VISIBLE && etKitapcik != null
            ? etKitapcik.getText().toString().trim() : "";

        if (formHasAdSoyad && ad.isEmpty()) {
            if (etAd != null) etAd.setError("Ad gerekli");
            return;
        }
        if (formHasNumara && numara.isEmpty()) {
            if (etNumara != null) etNumara.setError("Numara gerekli");
            return;
        }

        String kayitAdi = ad;
        if (kayitAdi.isEmpty()) {
            if (!numara.isEmpty()) kayitAdi = numara;
            else if (!kitapcikStr.isEmpty()) kayitAdi = "Kitapçık " + kitapcikStr;
            else kayitAdi = "Öğrenci";
        }

        kagit.ad = kayitAdi;
        kagit.numara = numara.isEmpty() ? null : numara;
        kagit.sinif = sinifStr.isEmpty() ? null : sinifStr;
        kagit.kitapcik = kitapcikStr.isEmpty() ? null : kitapcikStr;

        executor.execute(() -> {
            db.ogrenciKagidiDao().update(kagit);
            runOnUiThread(() -> {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(kagit.ad);
                }
                Toast.makeText(this, "Bilgiler kaydedildi", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void hesaplaToplam() {
        double toplamNet = 0;
        if (kagit.sonuclarJson != null && !kagit.sonuclarJson.isEmpty()) {
            Type type = new TypeToken<Map<String, Map<String, Object>>>(){}.getType();
            Map<String, Map<String, Object>> sonuclar = gson.fromJson(kagit.sonuclarJson, type);
            if (sonuclar != null) {
                for (Map<String, Object> s : sonuclar.values()) {
                    Object netObj = s.get("net");
                    if (netObj != null) toplamNet += ((Number) netObj).doubleValue();
                }
            }
        }
        if (tvToplam != null) {
            tvToplam.setText(String.format(Locale.getDefault(), "Toplam Net : %.2f", toplamNet));
        }
        if (tvPybs != null) {
            tvPybs.setText(String.format(Locale.getDefault(), "Pybs : %.0f", toplamNet * 6.7));
        }
    }

    private void setupDersSpinner() {
        if (!formHasCevap || cevapAlanlar.isEmpty()) {
            return;
        }
        List<String> dersler = new ArrayList<>();
        for (OptikFormAlan alan : cevapAlanlar) {
            dersler.add(alan.ders != null && !alan.ders.isEmpty() ? alan.ders : alan.etiket);
        }
        spinnerDers.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, dersler));
        spinnerDers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                gosterDersCevaplari(cevapAlanlar.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        gosterDersCevaplari(cevapAlanlar.get(0));
    }

    private void gosterDersCevaplari(OptikFormAlan alan) {
        cevaplarLayout.removeAllViews();
        String dersAdi = alan.ders != null && !alan.ders.isEmpty() ? alan.ders : alan.etiket;

        Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
        Map<String, List<String>> tumCevaplar = kagit.cevaplarJson != null ?
            gson.fromJson(kagit.cevaplarJson, type) : new HashMap<>();
        List<String> ogrCevaplar = tumCevaplar.getOrDefault(dersAdi, new ArrayList<>());

        executor.execute(() -> {
            CevapAnahtari anahtar = db.cevapAnahtariDao().getBySinavAndAlan(sinavId, alan.id);
            Type listType = new TypeToken<List<String>>(){}.getType();
            List<String> anahtarCevaplar = anahtar != null && anahtar.cevaplarJson != null ?
                gson.fromJson(anahtar.cevaplarJson, listType) : new ArrayList<>();

            int toplamSoru = alan.blokSayisi * alan.bloktakiVeriSayisi;
            if (toplamSoru <= 0) toplamSoru = ogrCevaplar.size();
            int dogru = 0, yanlis = 0, bos = 0;
            for (int i = 0; i < toplamSoru; i++) {
                String ogr = i < ogrCevaplar.size() ? ogrCevaplar.get(i) : "";
                String anh = i < anahtarCevaplar.size() ? anahtarCevaplar.get(i) : "";
                if (ogr == null || ogr.isEmpty()) bos++;
                else if (ogr.equals(anh)) dogru++;
                else yanlis++;
            }
            int finalDogru = dogru, finalYanlis = yanlis, finalBos = bos;
            double net = com.testplus.app.utils.NetHesaplayici.hesaplaNet(dogru, yanlis, sinav != null ? sinav.yanlisCezasi : Constants.CEZA_YOK);

            String desen = alan.desen != null ? alan.desen : "ABCD";
            char[] secenekler = desen.toCharArray();
            int finalToplamSoru = toplamSoru;

            runOnUiThread(() -> {
                tvDogru.setText("D : " + finalDogru);
                tvYanlis.setText("Y : " + finalYanlis);
                tvBos.setText("B : " + finalBos);
                tvNet.setText(String.format(Locale.getDefault(), "N : %.2f", net));

                for (int i = 0; i < finalToplamSoru; i++) {
                    final int idx = i;
                    LinearLayout satir = new LinearLayout(this);
                    satir.setOrientation(LinearLayout.HORIZONTAL);
                    satir.setPadding(8, 4, 8, 4);
                    satir.setGravity(android.view.Gravity.CENTER_VERTICAL);

                    TextView tvNo = new TextView(this);
                    tvNo.setText((i + alan.ilkSoruNumarasi) + ")");
                    tvNo.setTextSize(13);
                    tvNo.setTextColor(0xFF4CAF50);
                    tvNo.setMinWidth(48);
                    satir.addView(tvNo);

                    String ogrCevap = idx < ogrCevaplar.size() ? ogrCevaplar.get(idx) : "";
                    String anhCevap = idx < anahtarCevaplar.size() ? anahtarCevaplar.get(idx) : "";

                    for (char c : secenekler) {
                        String cevap = String.valueOf(c);
                        boolean secili = cevap.equals(ogrCevap);
                        boolean dogru2 = cevap.equals(anhCevap);

                        TextView tvCevap = new TextView(this);
                        tvCevap.setText(cevap);
                        tvCevap.setTextSize(14);
                        tvCevap.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tvCevap.setGravity(android.view.Gravity.CENTER);
                        tvCevap.setIncludeFontPadding(false);
                        int px = (int) (44 * getResources().getDisplayMetrics().density);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
                        lp.setMargins(4, 0, 4, 0);
                        tvCevap.setLayoutParams(lp);
                        tvCevap.setPadding(0, 0, 0, 0);

                        if (secili) {
                            if (dogru2) {
                                tvCevap.setBackgroundResource(R.drawable.circle_green);
                                tvCevap.setTextColor(0xFFFFFFFF);
                            } else {
                                tvCevap.setBackgroundResource(R.drawable.circle_red_stroke);
                                tvCevap.setTextColor(0xFFE53935);
                            }
                        } else {
                            tvCevap.setBackgroundResource(R.drawable.circle_gray);
                            tvCevap.setTextColor(0xFF757575);
                        }
                        satir.addView(tvCevap);
                    }
                    cevaplarLayout.addView(satir);
                    View divider = new View(this);
                    divider.setBackgroundColor(0xFFEEEEEE);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    divider.setLayoutParams(dlp);
                    cevaplarLayout.addView(divider);
                }
            });
        });
    }
}
