package com.example.tryagain;


import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ViewHolder> {

    public List<File> imageFiles;
    private Context context;
    private SparseBooleanArray selectedItems = new SparseBooleanArray();

    public ImageAdapter(Context context, List<File> imageFiles) {
        this.context = context;
        this.imageFiles = imageFiles;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File imageFile = imageFiles.get(position);
        holder.image.setImageBitmap(BitmapFactory.decodeFile(imageFile.getAbsolutePath()));

        holder.checkBox.setChecked(selectedItems.get(position, false));
        holder.itemView.setOnClickListener(v -> {
            boolean newState = !selectedItems.get(position, false);
            if (newState) {
                selectedItems.put(position, true);
            } else {
                selectedItems.delete(position);
            }
            notifyItemChanged(position);
        });

        holder.checkBox.setOnClickListener(v -> {
            boolean isChecked = holder.checkBox.isChecked();
            if (isChecked) {
                selectedItems.put(position, true);
            } else {
                selectedItems.delete(position);
            }
        });
    }
    public void setImageFiles(List<File> imageFiles) {
        this.imageFiles = imageFiles;
        notifyDataSetChanged();
    }
    public void clearSelection() {
        selectedItems.clear();
        notifyDataSetChanged();
    }

    public List<File> getSelectedItems() {
        List<File> selectedFiles = new ArrayList<>();
        for (int i = 0; i < selectedItems.size(); i++) {
            int key = selectedItems.keyAt(i);
            if (selectedItems.get(key)) {
                selectedFiles.add(imageFiles.get(key));
            }
        }
        return selectedFiles;
    }

    @Override
    public int getItemCount() {
        return imageFiles.size();
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView image;
        CheckBox checkBox;


        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image);
            checkBox = itemView.findViewById(R.id.checkBox_select);

        }
    }
}