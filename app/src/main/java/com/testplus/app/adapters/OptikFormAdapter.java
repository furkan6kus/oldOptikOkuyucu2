package com.testplus.app.adapters;

import android.view.*;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.testplus.app.R;
import com.testplus.app.database.entities.OptikForm;
import java.util.ArrayList;
import java.util.List;

public class OptikFormAdapter extends RecyclerView.Adapter<OptikFormAdapter.ViewHolder> {
    public interface OnItemClickListener { void onItemClick(OptikForm form); }
    public interface OnMenuClickListener { void onMenuClick(OptikForm form); }

    private List<OptikForm> data = new ArrayList<>();
    private final OnItemClickListener clickListener;
    private final OnMenuClickListener menuClickListener;

    public OptikFormAdapter(OnItemClickListener click, OnMenuClickListener menu) {
        this.clickListener = click;
        this.menuClickListener = menu;
    }

    public void setData(List<OptikForm> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_optik_form, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OptikForm form = data.get(position);
        holder.tvAd.setText(form.ad);
        holder.tvBilgi.setText(form.kagit + " • " + form.yon);
        holder.itemView.setOnClickListener(v -> clickListener.onItemClick(form));
        holder.btnMore.setOnClickListener(v -> {
            if (menuClickListener != null) menuClickListener.onMenuClick(form);
        });
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAd, tvBilgi;
        ImageButton btnMore;
        ViewHolder(View view) {
            super(view);
            tvAd = view.findViewById(R.id.tvAd);
            tvBilgi = view.findViewById(R.id.tvBilgi);
            btnMore = view.findViewById(R.id.btnMore);
        }
    }
}
