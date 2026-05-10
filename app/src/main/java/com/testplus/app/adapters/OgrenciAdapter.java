package com.testplus.app.adapters;

import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.testplus.app.R;
import com.testplus.app.database.entities.OgrenciKagidi;
import java.lang.reflect.Type;
import java.util.*;

public class OgrenciAdapter extends RecyclerView.Adapter<OgrenciAdapter.ViewHolder> {
    public interface OnItemClickListener { void onItemClick(OgrenciKagidi kagit); }
    public interface OnItemLongClickListener { void onItemLongClick(OgrenciKagidi kagit); }

    private List<OgrenciKagidi> data = new ArrayList<>();
    private final OnItemClickListener clickListener;
    private final OnItemLongClickListener longClickListener;
    private Gson gson = new Gson();

    public OgrenciAdapter(OnItemClickListener click, OnItemLongClickListener longClick) {
        this.clickListener = click;
        this.longClickListener = longClick;
    }

    public void setData(List<OgrenciKagidi> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ogrenci, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OgrenciKagidi kagit = data.get(position);
        holder.tvAd.setText(kagit.ad);
        holder.tvNumara.setText(kagit.numara != null ? "No: " + kagit.numara : "");
        holder.tvSinif.setText(kagit.sinif != null ? "Sınıf: " + kagit.sinif : "");

        double toplamNet = 0;
        if (kagit.sonuclarJson != null) {
            try {
                Type type = new TypeToken<Map<String, Map<String, Object>>>(){}.getType();
                Map<String, Map<String, Object>> sonuclar = gson.fromJson(kagit.sonuclarJson, type);
                for (Map<String, Object> s : sonuclar.values()) {
                    Object netObj = s.get("net");
                    if (netObj != null) toplamNet += ((Number) netObj).doubleValue();
                }
            } catch (Exception e) { /* ignore */ }
        }
        holder.tvNet.setText(String.format(Locale.getDefault(), "Net: %.2f", toplamNet));
        holder.itemView.setOnClickListener(v -> clickListener.onItemClick(kagit));
        holder.itemView.setOnLongClickListener(v -> { longClickListener.onItemLongClick(kagit); return true; });
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAd, tvNumara, tvSinif, tvNet;
        ViewHolder(View view) {
            super(view);
            tvAd = view.findViewById(R.id.tvAd);
            tvNumara = view.findViewById(R.id.tvNumara);
            tvSinif = view.findViewById(R.id.tvSinif);
            tvNet = view.findViewById(R.id.tvNet);
        }
    }
}
