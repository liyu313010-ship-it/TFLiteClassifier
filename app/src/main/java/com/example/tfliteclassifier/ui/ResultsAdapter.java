package com.example.tfliteclassifier.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tfliteclassifier.R;
import com.example.tfliteclassifier.classifier.Recognition;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部结果列表适配器：每项显示中文名、英文名、置信度百分比和进度条。
 */
public class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.ViewHolder> {

    private List<Recognition> results = new ArrayList<>();

    public void updateResults(List<Recognition> newResults) {
        int oldSize = results.size();
        this.results = new ArrayList<>(newResults);
        int sharedSize = Math.min(oldSize, results.size());
        if (sharedSize > 0) {
            notifyItemRangeChanged(0, sharedSize);
        }
        if (results.size() > oldSize) {
            notifyItemRangeInserted(oldSize, results.size() - oldSize);
        } else if (oldSize > results.size()) {
            notifyItemRangeRemoved(results.size(), oldSize - results.size());
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Recognition recognition = results.get(position);
        int rank = position + 1;
        holder.rank.setText(holder.itemView.getContext().getString(R.string.result_rank, rank));
        holder.titleZh.setText(recognition.getTitleZh());
        holder.titleEn.setText(recognition.getTitle());
        holder.confidence.setText(holder.itemView.getContext().getString(
                R.string.confidence_format,
                recognition.getConfidence() * 100));
        holder.progress.setProgress(Math.round(recognition.getConfidence() * 100));
        holder.itemView.setContentDescription(holder.itemView.getContext().getString(
                R.string.result_accessibility,
                rank,
                recognition.getTitleZh(),
                recognition.getTitle(),
                recognition.getConfidence() * 100));
    }

    @Override
    public int getItemCount() {
        return results.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView rank;
        final TextView titleZh;
        final TextView titleEn;
        final TextView confidence;
        final ProgressBar progress;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            rank = itemView.findViewById(R.id.result_rank);
            titleZh = itemView.findViewById(R.id.result_title_zh);
            titleEn = itemView.findViewById(R.id.result_title_en);
            confidence = itemView.findViewById(R.id.result_confidence);
            progress = itemView.findViewById(R.id.result_progress);
        }
    }
}
