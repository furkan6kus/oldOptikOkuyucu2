package com.testplus.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.testplus.app.R;
import com.testplus.app.database.AppDatabase;
import com.testplus.app.database.entities.Sinav;
import com.testplus.app.fragments.KagitlarFragment;
import com.testplus.app.fragments.AnahtarlarFragment;
import com.testplus.app.utils.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SinavDetayActivity extends AppCompatActivity {
    private long sinavId;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private ExtendedFloatingActionButton fab;
    private static final int REQUEST_KAGIT = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sinav_detay);
        sinavId = getIntent().getLongExtra(Constants.EXTRA_SINAV_ID, -1);
        AppDatabase db = AppDatabase.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        executor.execute(() -> {
            Sinav sinav = db.sinavDao().getById(sinavId);
            if (sinav != null) {
                runOnUiThread(() -> {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setTitle(sinav.ad);
                    }
                });
            }
        });

        fab = findViewById(R.id.fabKagitOku);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(this, KagitOkuActivity.class);
            intent.putExtra(Constants.EXTRA_SINAV_ID, sinavId);
            startActivityForResult(intent, REQUEST_KAGIT);
        });

        TabLayout tabLayout = findViewById(R.id.tabLayout);
        tabLayout.addTab(tabLayout.newTab().setText("Kağıtlar"));
        tabLayout.addTab(tabLayout.newTab().setText("Anahtarlar"));

        loadFragment(0);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                loadFragment(tab.getPosition());
                fab.setVisibility(tab.getPosition() == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void loadFragment(int position) {
        Bundle args = new Bundle();
        args.putLong(Constants.EXTRA_SINAV_ID, sinavId);
        if (position == 0) {
            KagitlarFragment fragment = new KagitlarFragment();
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, fragment).commit();
        } else {
            AnahtarlarFragment fragment = new AnahtarlarFragment();
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, fragment).commit();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_KAGIT && resultCode == RESULT_OK) {
            loadFragment(0);
        }
    }
}
