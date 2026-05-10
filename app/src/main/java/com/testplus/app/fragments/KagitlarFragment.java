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
import com.testplus.app.R;
import com.testplus.app.activities.OgrenciDetayActivity;
import com.testplus.app.adapters.OgrenciAdapter;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.utils.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KagitlarFragment extends Fragment {
    private RecyclerView recyclerView;
    private OgrenciAdapter adapter;
    private AppDatabase db;
    private long sinavId;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_kagitlar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getInstance(requireContext());
        sinavId = getArguments() != null ? getArguments().getLong(Constants.EXTRA_SINAV_ID) : -1;
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new OgrenciAdapter(
            kagit -> {
                Intent intent = new Intent(requireContext(), OgrenciDetayActivity.class);
                intent.putExtra(Constants.EXTRA_OGRENCI_KAGIDI_ID, kagit.id);
                intent.putExtra(Constants.EXTRA_SINAV_ID, sinavId);
                startActivity(intent);
            },
            kagit -> new AlertDialog.Builder(requireContext())
                .setTitle("Kağıdı Sil")
                .setMessage(kagit.ad + " silinsin mi?")
                .setPositiveButton("Sil", (d, w) -> executor.execute(() -> db.ogrenciKagidiDao().deleteById(kagit.id)))
                .setNegativeButton("İptal", null)
                .show()
        );
        recyclerView.setAdapter(adapter);
        db.ogrenciKagidiDao().getBySinavId(sinavId).observe(getViewLifecycleOwner(), list -> adapter.setData(list));
    }
}
