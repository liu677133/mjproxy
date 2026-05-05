package com.diaryproxy.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * v1.5.1+：PDF 文本提取，基于 com.tom-roush:pdfbox-android。
 *
 * <p>给 {@link DiaryProxyServer} / {@link AttachmentSupport} 用：把 PDF 字节数组转纯文本，
 * 用于附件注入到 user message。失败时回退到 UTF-8 直解（保持向后兼容老路径）。
 *
 * <p>调用前必须先在 Application.onCreate 调过 {@code PDFBoxResourceLoader.init(context)}
 * （否则字体加载失败）。我们在 {@link DiaryProxyApplication#onCreate()} 已经处理。
 */
final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    /** v1.5.4+：PDF 文本提取硬上限。超过则返回提示而不进 PDFBox（解决 PE-1 OOM 风险）。 */
    private static final int PDF_SIZE_HARD_LIMIT_BYTES = 8 * 1024 * 1024;

    /**
     * @param bytes PDF 文件字节
     * @return 提取出的纯文本（多页拼接），失败返回空串
     */
    static String extractText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        // v1.5.4+：PDFBox 加载到内存的尺寸放大约 3-4x，低端机 32MB PDF 直接 OOM。
        // 入口卡 8MB 上限，超过给提示而不进 PDFBox。
        if (bytes.length > PDF_SIZE_HARD_LIMIT_BYTES) {
            return "(PDF 过大，请上传 8MB 以内)";
        }
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text == null ? "" : text;
        } catch (Throwable error) {
            return "";
        }
    }

    /** 简易 PDF 嗅探：PDF 文件以 "%PDF-" 开头（前 5 字节）。 */
    static boolean looksLikePdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    /** 给定 mime + bytes，决议输出文本：PDF 走 pdfbox 解析，其他走 UTF-8 直解。 */
    static String decodeAttachmentText(String mime, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        boolean isPdf = "application/pdf".equalsIgnoreCase(mime) || looksLikePdf(bytes);
        if (isPdf) {
            String extracted = extractText(bytes);
            if (!extracted.isEmpty()) return extracted;
            // 提取失败 → 给个明确提示，而不是塞乱码
            return "(PDF 文本提取失败：可能是扫描件 / 加密 / 格式损坏)";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
