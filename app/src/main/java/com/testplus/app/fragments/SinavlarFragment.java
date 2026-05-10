package com.testplus.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.testplus.app.R;
import com.testplus.app.activities.SinavDetayActivity;
import com.testplus.app.activities.YeniSinavActivity;
import com.testplus.app.adapters.SinavAdapter;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.Sinav;
import com.testplus.app.utils.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SinavlarFragment extends Fragment {
    private RecyclerView recyclerView;
    private SinavAdapter adapter;
    private AppDatabase db;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sinavlar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getInstance(requireContext());
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new SinavAdapter(
            sinav -> {
                Intent intent = new Intent(requireContext(), SinavDetayActivity.class);
                intent.putExtra(Constants.EXTRA_SINAV_ID, sinav.id);
                startActivity(intent);
            },
            sinav -> showOptionsDialog(sinav)
        );
        recyclerView.setAdapter(adapter);

        db.sinavDao().getAll().observe(getViewLifecycleOwner(), sinavlar -> adapter.setData(sinavlar));

        FloatingActionButton fab = view.findViewById(R.id.fabEkle);
        fab.setOnClickListener(v -> startActivity(new Intent(requireContext(), YeniSinavActivity.class)));
    }

    private void showOptionsDialog(Sinav sinav) {
        String[] options = {"Düzenle", "Sil"};
        new AlertDialog.Builder(requireContext())
            .setTitle(sinav.ad)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(requireContext(), YeniSinavActivity.class);
                    intent.putExtra(Constants.EXTRA_IS_EDIT, true);
                    intent.putExtra(Constants.EXTRA_SINAV_ID, sinav.id);
                    startActivity(intent);
                } else {
                    new AlertDialog.Builder(requireContext())
                        .setTitle("Sınavı Sil")
                        .setMessage("\"" + sinav.ad + "\" silinsin mi?")
                        .setPositiveButton("Sil", (d, w) -> executor.execute(() -> {
                            db.cevapAnahtariDao().deleteBySinavId(sinav.id);
                            db.ogrenciKagidiDao().deleteBySinavId(sinav.id);
                            db.sinavDao().deleteById(sinav.id);
                        }))
                        .setNegativeButton("İptal", null)
                        .show();
                }
            })
            .show();
    }
}
