package com.diankuang.xiaomirom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP：UTF-8 强制解码 + 重试 + Range 探测（无第三方依赖）。 */
public final class Http {
    public static final String BASE = "https://xiaomirom.com";
    public static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private Http() { }

    public static class HttpError extends IOException {
        public final int code;
        HttpError(int code, String url) {
            super("HTTP " + code + (code == 403
                    ? "（被 CDN 拒绝，换网络或换下载源试试）" : "") + " " + url);
            this.code = code;
        }
    }

    private static HttpURLConnection open(String url, long rangeFrom) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (rangeFrom >= 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
        return c;
    }

    /** GET 整个页面（UTF-8 强解，站点不带 charset 头），失败重试 3 次。 */
    public static String fetch(String url) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            HttpURLConnection c = open(url, -1);
            try {
                int code = c.getResponseCode();
                if (code == 200) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    return new String(bos.toByteArray(), "UTF-8");
                }
                last = new HttpError(code, url);
            } catch (IOException e) {
                last = e;
            } finally {
                c.disconnect();
            }
            try { Thread.sleep(800L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw last;
    }

    /**
     * Range 探测远端文件大小（不下载正文）：优先 206 的 Content-Range: bytes x-y/total，
     * 退回 200 的 Content-Length。返回 -1 = 无法得知。
     */
    public static long probeSize(String url) throws IOException {
        HttpURLConnection c = open(url, 0);
        c.setRequestMethod("GET");
        try {
            int code = c.getResponseCode();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range"); // bytes 0-0/1234567
                if (cr != null && cr.contains("/")) {
                    String t = cr.substring(cr.lastIndexOf('/') + 1).trim();
                    try { return Long.parseLong(t); } catch (NumberFormatException ignored) { }
                }
                return -1;
            }
            if (code == 200) {
                long len = c.getContentLength();
                return len; // 可能 -1（未知长度）
            }
            throw new HttpError(code, url);
        } finally {
            c.disconnect();
        }
    }

    /** 流式 Range 分块回调；返回 false 表示中止（抛 IOException("已取消")）。 */
    public interface StreamCb { boolean onChunk(byte[] data, int len) throws IOException; }

    /**
     * 精确 Range 流式读取 bytes=from-(from+len-1)，要求 206。
     * 服务器不支持分段（200）时抛 IOException。
     */
    public static void streamRange(String url, long from, long len, StreamCb cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (from + len - 1));
        try {
            int code = c.getResponseCode();
            if (code == 200) throw new IOException("服务器不支持分段读取(Range)");
            if (code != 206) throw new HttpError(code, url);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[256 * 1024];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                if (n == 0) continue;
                left -= n;
                if (!cb.onChunk(buf, n)) throw new IOException("已取消");
            }
            in.close();
            if (left > 0) throw new IOException("分段读取不完整");
        } finally {
            c.disconnect();
        }
    }

    /** 把站内相对路径补成绝对 URL（对齐 Python 的 urljoin 行为）。 */
    public static String urlJoin(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("//")) return "https:" + href;
        return BASE + (href.startsWith("/") ? href : "/" + href);
    }
}
