package com.testplus.app.adapters;

import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.testplus.app.R;
import com.testplus.app.database.entities.Sinav;
import java.text.SimpleDateFormat;
import java.util.*;

public class SinavAdapter extends RecyclerView.Adapter<SinavAdapter.ViewHolder> {
    public interface OnItemClickListener { void onItemClick(Sinav sinav); }
    public interface OnItemLongClickListener { void onItemLongClick(Sinav sinav); }

    private List<Sinav> data = new ArrayList<>();
    private final OnItemClickListener clickListener;
    private final OnItemLongClickListener longClickListener;
    private SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());

    public SinavAdapter(OnItemClickListener click, OnItemLongClickListener longClick) {
        this.clickListener = click;
        this.longClickListener = longClick;
    }

    public void setData(List<Sinav> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sinav, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sinav sinav = data.get(position);
        holder.tvAd.setText(sinav.ad);
        String tarih = sinav.sinavTarihi > 0 ? sdf.format(new Date(sinav.sinavTarihi)) : "";
        holder.tvTarih.setText(tarih);
        holder.itemView.setOnClickListener(v -> clickListener.onItemClick(sinav));
        holder.itemView.setOnLongClickListener(v -> { longClickListener.onItemLongClick(sinav); return true; });
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAd, tvTarih;
        ViewHolder(View view) {
            super(view);
            tvAd = view.findViewById(R.id.tvAd);
            tvTarih = view.findViewById(R.id.tvTarih);
        }
    }
}
