package com.beaconiq.trilateration.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.beaconiq.trilateration.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class BeaconCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_CLOSEST = 0;
    private static final int VIEW_TYPE_NORMAL = 1;

    private final List<BeaconCardItem> items = new ArrayList<>();

    public void updateItems(List<BeaconCardItem> newItems) {
        items.clear();
        items.addAll(newItems);
        Collections.sort(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return (position == 0 && !items.isEmpty() && items.get(0).isClosest())
                ? VIEW_TYPE_CLOSEST : VIEW_TYPE_NORMAL;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_CLOSEST) {
            View view = inflater.inflate(R.layout.item_beacon_card_closest, parent, false);
            return new ClosestCardViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_beacon_card_normal, parent, false);
            return new NormalCardViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BeaconCardItem item = items.get(position);
        if (holder instanceof ClosestCardViewHolder) {
            bindClosest((ClosestCardViewHolder) holder, item);
        } else {
            bindNormal((NormalCardViewHolder) holder, item);
        }
    }

    private void bindClosest(ClosestCardViewHolder h, BeaconCardItem item) {
        h.label.setText("Beacon " + item.getLabel());
        h.distance.setText(String.format(Locale.US, "%.1f m", item.getFilteredDistanceMeters()));
        h.rssi.setText(item.getLastRawRssi() + " dBm");
        h.rssi.setTextColor(rssiColor(h.itemView, item.getLastRawRssi()));
    }

    private void bindNormal(NormalCardViewHolder h, BeaconCardItem item) {
        h.label.setText("Beacon " + item.getLabel());
        h.distance.setText(String.format(Locale.US, "%.1f m", item.getFilteredDistanceMeters()));
        h.rssi.setText(item.getLastRawRssi() + " dBm");
        h.rssi.setTextColor(rssiColor(h.itemView, item.getLastRawRssi()));
    }

    private int rssiColor(View view, int rssi) {
        int colorRes;
        if (rssi >= -60) colorRes = R.color.status_ok;
        else if (rssi >= -80) colorRes = R.color.status_warn;
        else colorRes = R.color.status_alert;
        return ContextCompat.getColor(view.getContext(), colorRes);
    }

    static class ClosestCardViewHolder extends RecyclerView.ViewHolder {
        final TextView label, distance, rssi;
        final LinearLayout actionContainer;

        ClosestCardViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.beacon_card_label);
            distance = v.findViewById(R.id.beacon_card_distance);
            rssi = v.findViewById(R.id.beacon_card_rssi);
            actionContainer = v.findViewById(R.id.beacon_card_actions);
        }
    }

    static class NormalCardViewHolder extends RecyclerView.ViewHolder {
        final TextView label, distance, rssi;

        NormalCardViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.beacon_card_label);
            distance = v.findViewById(R.id.beacon_card_distance);
            rssi = v.findViewById(R.id.beacon_card_rssi);
        }
    }
}
