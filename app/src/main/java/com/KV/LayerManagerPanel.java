package com.KV;

import android.app.Activity;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LayerManagerPanel {

    private Activity activity;
    private View panelView;
    private KeyView keyView;
    private KeyManager keyManager;
    private GlobalSettings globalSettings;
    
    private RecyclerView layerRecyclerView;
    private LayerAdapter adapter;
    
    private ItemTouchHelper itemTouchHelper;
    
    public interface OnLayerChangedListener {
        void onLayerChanged();
    }
    
    private OnLayerChangedListener listener;
    
    public LayerManagerPanel(Activity activity, View panelView, KeyView keyView) {
        this.activity = activity;
        this.panelView = panelView;
        this.keyView = keyView;
        this.keyManager = keyView.getKeyManager();
        this.globalSettings = GlobalSettings.getInstance(activity);
        
        initViews();
        setupRecyclerView();
        applyDarkMode();
    }
    
    private void applyDarkMode() {
        boolean isDarkMode = globalSettings.darkModeEnabled;
        int bgColor = isDarkMode ? 0xFF2C2C2C : 0xFFF5F5F5;
        panelView.setBackgroundColor(bgColor);
        
        TextView closeBtn = panelView.findViewById(R.id.closeLayerPanelBtn);
        if (closeBtn != null) {
            closeBtn.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF333333);
        }
        
        Button autoArrangeBtn = panelView.findViewById(R.id.autoArrangeBtn);
        if (autoArrangeBtn != null) {
            autoArrangeBtn.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF333333);
        }
        
        Button resetZIndexBtn = panelView.findViewById(R.id.resetZIndexBtn);
        if (resetZIndexBtn != null) {
            resetZIndexBtn.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF333333);
        }
        
        if (layerRecyclerView != null) {
            int recyclerBg = isDarkMode ? 0xFF3C3C3C : 0xFFFFFFFF;
            layerRecyclerView.setBackgroundColor(recyclerBg);
        }
    }
    
    public void onDarkModeChanged() {
        applyDarkMode();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    
    public void setOnLayerChangedListener(OnLayerChangedListener listener) {
        this.listener = listener;
    }
    
    private void initViews() {
        layerRecyclerView = panelView.findViewById(R.id.layerRecyclerView);
        
        View closeBtn = panelView.findViewById(R.id.closeLayerPanelBtn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                panelView.setVisibility(View.GONE);
            });
        }
        
        View autoArrangeBtn = panelView.findViewById(R.id.autoArrangeBtn);
        if (autoArrangeBtn != null) {
            autoArrangeBtn.setOnClickListener(v -> autoArrange());
        }
        
        View resetZIndexBtn = panelView.findViewById(R.id.resetZIndexBtn);
        if (resetZIndexBtn != null) {
            resetZIndexBtn.setOnClickListener(v -> resetAllZIndex());
        }
    }
    
    private void setupRecyclerView() {
        layerRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new LayerAdapter();
        layerRecyclerView.setAdapter(adapter);
        
        ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.START | ItemTouchHelper.END;
                return makeMovementFlags(dragFlags, 0);
            }
            
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, 
                                  RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getAdapterPosition();
                int toPosition = target.getAdapterPosition();
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false;
                }
                
                KeyData moved = adapter.keys.remove(fromPosition);
                adapter.keys.add(toPosition, moved);
                adapter.notifyItemMoved(fromPosition, toPosition);
                
                updateZIndexFromOrder(false);
                
                return true;
            }

            @Override
            public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                updateZIndexFromOrder(true);
            }
            
            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            }
            
            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
            
            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }
        };
        
        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(layerRecyclerView);
    }
    
    public void refreshList() {
        if (adapter != null) {
            adapter.updateKeys();
        }
    }
    
    private void updateZIndexFromOrder(boolean persist) {
        List<KeyData> adapterKeys = adapter.keys;
        
        // RecyclerView 顶部 = 最上层（最大 zIndex）
        // 顶部位置 0 -> zIndex = N（最大）
        // 底部位置 N-1 -> zIndex = 1（最小）
        for (int i = 0; i < adapterKeys.size(); i++) {
            int newZIndex = adapterKeys.size() - i;
            KeyData adapterKey = adapterKeys.get(i);
            adapterKey.zIndex = newZIndex;
        }
        if (!persist) return;
        keyManager.saveToPreferences();
        keyManager.notifyDataChanged();
        keyView.invalidate();
        
        if (listener != null) {
            listener.onLayerChanged();
        }
        
        adapter.notifyDataSetChanged();
    }
    
    private void autoArrange() {
        List<KeyData> adapterKeys = adapter.keys;
        List<KeyData> managerKeys = keyManager.getKeys();
        
        // 按位置排序：Y 为主，越靠下的按键层级越高（zIndex 越大）。
        // 只有 Y 相同时才按 X 从左到右稳定排序。
        Collections.sort(adapterKeys, (a, b) -> {
            int byY = Float.compare(b.centerY, a.centerY);
            return byY != 0 ? byY : Float.compare(a.centerX, b.centerX);
        });
        
        // 分配 zIndex：顶部（列表前面）= 最大 zIndex
        for (int i = 0; i < adapterKeys.size(); i++) {
            int newZIndex = adapterKeys.size() - i;
            KeyData adapterKey = adapterKeys.get(i);
            adapterKey.zIndex = newZIndex;
            
        }
        
        keyView.invalidate();
        keyView.requestLayout();
        
        if (listener != null) {
            listener.onLayerChanged();
        }
        
        refreshList();
    }
    
    private void resetAllZIndex() {
        // 使用 KeyManager 的统一规范化方法
        keyManager.normalizeZIndexes();
        keyManager.saveToPreferences();
        keyView.invalidate();
        keyView.requestLayout();
        refreshList();
        
        if (listener != null) {
            listener.onLayerChanged();
        }
    }
    
    public void show() {
        refreshList();
        applyDarkMode();
        panelView.setVisibility(View.VISIBLE);
    }
    
    public void hide() {
        panelView.setVisibility(View.GONE);
    }
    
    public boolean isVisible() {
        return panelView.getVisibility() == View.VISIBLE;
    }
    
    private class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.ViewHolder> {
        
        private List<KeyData> keys = new ArrayList<>();
        
        public LayerAdapter() {
            updateKeys();
        }
        
        public void updateKeys() {
            // 使用 KeyManager 的统一排序方法（zIndex 降序）
            // 列表顶部显示最上层按键
            keys = keyManager.getKeysSortedByZIndex();
            notifyDataSetChanged();
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.layer_list_item, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            KeyData key = keys.get(position);
            boolean isDarkMode = globalSettings.darkModeEnabled;
            
            String displayName = getDisplayName(key);
            holder.keyNameText.setText(displayName);
            holder.zIndexText.setText(String.valueOf(key.zIndex));
            
            int color = key.fillColorUp;
            holder.colorIndicator.setBackgroundColor(color);
            
            KeyData selectedKey = keyManager.getSelectedKey();
            if (selectedKey != null && key.id.equals(selectedKey.id)) {
                if (isDarkMode) {
                    holder.itemView.setBackgroundColor(0xFF3A5A7A);
                } else {
                    holder.itemView.setBackgroundColor(0xFFE3F2FD);
                }
            } else {
                if (isDarkMode) {
                    holder.itemView.setBackgroundColor(0xFF3C3C3C);
                } else {
                    holder.itemView.setBackgroundColor(Color.WHITE);
                }
            }
            
            int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
            holder.keyNameText.setTextColor(textColor);
            holder.zIndexText.setTextColor(isDarkMode ? 0xFFAAAAAA : 0xFF999999);
            
            if (holder.dragHandle != null) {
                holder.dragHandle.setColorFilter(isDarkMode ? 0xFFFFFFFF : 0xFF999999);
            }
            
            holder.itemView.setOnClickListener(v -> {
                keyView.selectKeyById(key.id);
                notifyDataSetChanged();
            });
            
            holder.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });
            
            holder.dragHandle.setOnClickListener(v -> {});
        }
        
        @Override
        public int getItemCount() {
            return keys.size();
        }
        
        private String getDisplayName(KeyData key) {
            if (key.displayLabel != null && !key.displayLabel.isEmpty()) {
                String lower = key.displayLabel.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("kps") || lower.equals("total")) {
                    return key.displayLabel;
                }
                return key.displayLabel;
            }
            if (key.boundKey != null && !key.boundKey.equals("未绑定")) {
                return key.boundKey;
            }
            return "未命名按键";
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView dragHandle;
            View colorIndicator;
            TextView keyNameText;
            TextView zIndexText;
            
            public ViewHolder(View itemView) {
                super(itemView);
                dragHandle = itemView.findViewById(R.id.dragHandle);
                colorIndicator = itemView.findViewById(R.id.colorIndicator);
                keyNameText = itemView.findViewById(R.id.keyNameText);
                zIndexText = itemView.findViewById(R.id.zIndexText);
            }
        }
    }
}
