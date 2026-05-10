package com.testplus.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import com.testplus.app.R;
import com.testplus.app.activities.CevapAnahtariActivity;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.*;
import com.testplus.app.utils.Constants;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnahtarlarFragment extends Fragment {
    private LinearLayout containerLayout;
    private AppDatabase db;
    private long sinavId;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anahtarlar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getInstance(requireContext());
        sinavId = getArguments() != null ? getArguments().getLong(Constants.EXTRA_SINAV_ID) : -1;
        containerLayout = view.findViewById(R.id.containerLayout);
        yukleAnahtarlar();
    }

    @Override
    public void onResume() {
        super.onResume();
        yukleAnahtarlar();
    }

    private void runOnUiThread(Runnable r) {
        FragmentActivity act = getActivity();
        if (act != null && isAdded()) act.runOnUiThread(r);
    }

    private void yukleAnahtarlar() {
        executor.execute(() -> {
            Sinav sinav = db.sinavDao().getById(sinavId);
            if (sinav == null) return;
            List<OptikFormAlan> alanlar = db.optikFormAlanDao().getByFormId(sinav.optikFormId);
            runOnUiThread(() -> {
                containerLayout.removeAllViews();
                boolean hasCevaplar = false;
                for (OptikFormAlan alan : alanlar) {
                    if (!Constants.TUR_CEVAPLAR.equals(alan.tur)) continue;
                    hasCevaplar = true;
                    View itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_anahtar, containerLayout, false);
                    TextView tvDers = itemView.findViewById(R.id.tvDers);
                    TextView tvDurum = itemView.findViewById(R.id.tvDurum);
                    Button btnDuzenle = itemView.findViewById(R.id.btnDuzenle);
                    tvDers.setText(alan.ders != null && !alan.ders.isEmpty() ? alan.ders : alan.etiket);

                    executor.execute(() -> {
                        CevapAnahtari anahtar = db.cevapAnahtariDao().getBySinavAndAlan(sinavId, alan.id);
                        runOnUiThread(() -> {
                            if (anahtar != null && anahtar.cevaplarJson != null) {
                                tvDurum.setText("Girildi ✓");
                                tvDurum.setTextColor(0xFF4CAF50);
                            } else {
                                tvDurum.setText("Girilmedi");
                                tvDurum.setTextColor(0xFFE53935);
                            }
                        });
                    });

                    btnDuzenle.setOnClickListener(v -> {
                        Intent intent = new Intent(requireContext(), CevapAnahtariActivity.class);
                        intent.putExtra(Constants.EXTRA_SINAV_ID, sinavId);
                        intent.putExtra(Constants.EXTRA_OPTIK_FORM_ALAN_ID, alan.id);
                        startActivity(intent);
                    });
                    containerLayout.addView(itemView);
                }
                if (!hasCevaplar) {
                    TextView tvEmpty = new TextView(requireContext());
                    tvEmpty.setText("Bu optik formda cevap alanı bulunmuyor.");
                    tvEmpty.setPadding(32, 32, 32, 32);
                    containerLayout.addView(tvEmpty);
                }
            });
        });
    }
}
