package com.zyvo.routercontrol;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.zyvo.routercontrol.databinding.ItemDeviceBinding;

import java.util.ArrayList;
import java.util.List;

/** Device list rows: icon, name, IP + MAC, status chip, block/unblock button. */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    public interface Listener {
        void onBlock(DevicesActivity.Device d);
        void onUnblock(DevicesActivity.Device d);
    }

    private final Listener listener;
    private final List<DevicesActivity.Device> items = new ArrayList<>();

    public DeviceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<DevicesActivity.Device> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDeviceBinding b = ItemDeviceBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DevicesActivity.Device d = items.get(position);
        h.b.nameText.setText(d.name);
        h.b.metaText.setText(d.ip + "  \u00b7  " + d.mac);
        h.b.avatarText.setText(initial(d.name));
        if (d.blocked) {
            h.b.statusChip.setVisibility(android.view.View.VISIBLE);
            h.b.actionButton.setText("Unblock");
            h.b.actionButton.setTextColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.ok));
            h.b.avatar.setCardBackgroundColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.danger_soft));
            h.b.avatarText.setTextColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.danger));
        } else {
            h.b.statusChip.setVisibility(android.view.View.GONE);
            h.b.actionButton.setText("Block");
            h.b.actionButton.setTextColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.danger));
            h.b.avatar.setCardBackgroundColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.accent_soft));
            h.b.avatarText.setTextColor(ContextCompat.getColor(
                    h.itemView.getContext(), R.color.accent_deep));
        }
        h.b.actionButton.setOnClickListener(v -> {
            if (d.blocked) listener.onUnblock(d);
            else listener.onBlock(d);
        });
    }

    private static String initial(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemDeviceBinding b;
        VH(ItemDeviceBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
