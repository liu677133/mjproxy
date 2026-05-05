package com.diaryproxy.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/** v1.5.0：附件草稿列表适配器（RecyclerView）。 */
final class AttachmentDraftAdapter extends RecyclerView.Adapter<AttachmentDraftAdapter.VH> {

    interface OnRemoveClickListener {
        void onRemove(ProxyStorageHelper.AttachmentRef ref, int position);
    }

    /** v1.5.5+：DPS-8 — 由 MainActivity 在每次刷新前调用 setTruncationConfig，决定是否提示"将截断"。 */
    interface TruncationConfigSupplier {
        boolean isTruncationEnabled();
        int getTruncationMaxChars();
    }

    private final List<ProxyStorageHelper.AttachmentRef> data;
    private final OnRemoveClickListener removeListener;
    private TruncationConfigSupplier truncationConfig;

    AttachmentDraftAdapter(List<ProxyStorageHelper.AttachmentRef> data, OnRemoveClickListener removeListener) {
        this.data = data;
        this.removeListener = removeListener;
    }

    void setTruncationConfig(TruncationConfigSupplier supplier) {
        this.truncationConfig = supplier;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attachment_draft, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ProxyStorageHelper.AttachmentRef ref = data.get(position);
        holder.tvName.setText(safe(ref.displayName));
        holder.tvMeta.setText(formatMeta(ref, truncationConfig));
        bindThumbnail(holder.ivThumb, ref);
        holder.btnRemove.setOnClickListener(v -> {
            int adapterPos = holder.getBindingAdapterPosition();
            if (adapterPos != RecyclerView.NO_POSITION && removeListener != null) {
                removeListener.onRemove(ref, adapterPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    private static String formatMeta(ProxyStorageHelper.AttachmentRef ref, TruncationConfigSupplier truncation) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(ref.mime)) builder.append(ref.mime);
        if (builder.length() > 0) builder.append(" · ");
        builder.append(AttachmentSupport.formatSize(ref.byteSize));
        // v1.5.5+：DPS-8 — 文档附件附加 "约 N 字"，若启用截断且超出上限再追加截断提示。
        if (ref.charCount >= 0) {
            builder.append(" · 约 ").append(ref.charCount).append(" 字");
            if (truncation != null && truncation.isTruncationEnabled()) {
                int max = truncation.getTruncationMaxChars();
                if (max > 0 && ref.charCount > max) {
                    builder.append("（将截断到 ").append(max).append(" 字）");
                }
            }
        }
        return builder.toString();
    }

    private static void bindThumbnail(ImageView view, ProxyStorageHelper.AttachmentRef ref) {
        if (view == null || ref == null) return;
        if (AttachmentSupport.isImageMime(ref.mime) && !TextUtils.isEmpty(ref.localPath)) {
            try {
                File file = new File(ref.localPath);
                if (!file.isFile()) {
                    view.setImageResource(android.R.drawable.ic_menu_gallery);
                    return;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                int targetSize = 96;
                while ((bounds.outWidth / sample) > targetSize || (bounds.outHeight / sample) > targetSize) {
                    sample *= 2;
                }
                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
                if (bitmap != null) {
                    view.setImageBitmap(bitmap);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        view.setImageResource(android.R.drawable.ic_menu_more);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final TextView tvName;
        final TextView tvMeta;
        final TextView btnRemove;

        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivAttachmentThumb);
            tvName = itemView.findViewById(R.id.tvAttachmentName);
            tvMeta = itemView.findViewById(R.id.tvAttachmentMeta);
            btnRemove = itemView.findViewById(R.id.btnAttachmentRemove);
        }
    }
}
