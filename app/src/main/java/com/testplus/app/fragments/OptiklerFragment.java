package com.testplus.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.testplus.app.R;
import com.testplus.app.activities.OptikFormKanvasActivity;
import com.testplus.app.activities.YeniOptikFormActivity;
import com.testplus.app.adapters.OptikFormAdapter;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.OptikForm;
import com.testplus.app.utils.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OptiklerFragment extends Fragment {
    private RecyclerView recyclerView;
    private OptikFormAdapter adapter;
    private AppDatabase db;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int REQUEST_YENI = 1001;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_optikler, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = AppDatabase.getInstance(requireContext());
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new OptikFormAdapter(
            form -> {
                Intent intent = new Intent(requireContext(), OptikFormKanvasActivity.class);
                intent.putExtra(Constants.EXTRA_OPTIK_FORM_ID, form.id);
                intent.putExtra("form_adi", form.ad);
                startActivity(intent);
            },
            this::showOptionsDialog
        );
        recyclerView.setAdapter(adapter);

        db.optikFormDao().getAll().observe(getViewLifecycleOwner(), forms -> adapter.setData(forms));

        FloatingActionButton fab = view.findViewById(R.id.fabEkle);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), YeniOptikFormActivity.class);
            startActivityForResult(intent, REQUEST_YENI);
        });
    }

    private void showOptionsDialog(OptikForm form) {
        String[] options = {"Düzenle", "Sil"};
        new AlertDialog.Builder(requireContext())
            .setTitle(form.ad)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(requireContext(), YeniOptikFormActivity.class);
                    intent.putExtra(Constants.EXTRA_IS_EDIT, true);
                    intent.putExtra(Constants.EXTRA_OPTIK_FORM_ID, form.id);
                    startActivityForResult(intent, REQUEST_YENI);
                } else {
                    new AlertDialog.Builder(requireContext())
                        .setTitle("Optik Formu Sil")
                        .setMessage("\"" + form.ad + "\" silinsin mi?")
                        .setPositiveButton("Sil", (d, w) -> executor.execute(() -> {
                            db.optikFormAlanDao().deleteByFormId(form.id);
                            db.optikFormDao().deleteById(form.id);
                        }))
                        .setNegativeButton("İptal", null)
                        .show();
                }
            })
            .show();
    }
}
