package com.diankuang.xiaomirom;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * payload.bin 在线分区提取（Java 移植自 xiaomirom_scraper.py 纯 Python 极速提取）：
 * 用 HTTP Range 只下载所选分区用到的字节，不下整包。
 * 仅支持整包 OTA（minor_version=0）且操作为 REPLACE / REPLACE_BZ / REPLACE_XZ /
 * ZERO / DISCARD；其余情况抛 IOException 由界面提示。
 */
public final class Payload {

    private Payload() { }

    // ============================================================ protobuf
    /** 极简 protobuf wire 解码（对齐 Python _pb_fields）。 */
    static final class Pb {
        private final byte[] b; private int i, n;
        int fno, wt; long v; byte[] vb;
        Pb(byte[] buf) { b = buf; n = buf.length; }
        boolean next() throws IOException {
            if (i >= n) return false;
            long key = varint();
            fno = (int) (key >>> 3); wt = (int) (key & 7);
            switch (wt) {
                case 0: v = varint(); return true;
                case 1: vbytes(8); return true;
                case 2: { long ln = varint(); vbytes((int) Math.min(ln, Integer.MAX_VALUE - 8L));
                    if (ln > Integer.MAX_VALUE - 8L) throw new IOException("protobuf 长度越界");
                    return true; }
                case 5: vbytes(4); return true;
                default: throw new IOException("未知 wire type " + wt);
            }
        }
        private long varint() throws IOException {
            long v = 0; int shift = 0;
            while (i < n) {
                int c = b[i++] & 0xFF;
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) break;
            }
            throw new IOException("protobuf varint 损坏");
        }
        private void vbytes(int len) throws IOException {
            if (len < 0 || i + len > n) throw new IOException("protobuf 长度越界");
            vb = new byte[len];
            System.arraycopy(b, i, vb, 0, len);
            i += len;
        }
    }

    // ============================================================ ZIP 定位
    static final class Info { int method; long start, comp, uncomp; }

    public interface ReadFn { byte[] read(long start, int len) throws IOException; }

    static long u16(byte[] b, int o) { return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8); }
    static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
             | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }
    static long u64(byte[] b, int o) { return u32(b, o) | (u32(b, o + 4) << 32); }
    static long be64(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 56) | ((b[o + 1] & 0xFFL) << 48)
             | ((b[o + 2] & 0xFFL) << 40) | ((b[o + 3] & 0xFFL) << 32)
             | ((b[o + 4] & 0xFFL) << 24) | ((b[o + 5] & 0xFFL) << 16)
             | ((b[o + 6] & 0xFFL) << 8) | (b[o + 7] & 0xFFL);
    }
    static long be32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16)
             | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }
    static boolean mag(byte[] b, int o, int... m) {
        for (int k = 0; k < m.length; k++) if ((b[o + k] & 0xFF) != m[k]) return false;
        return true;
    }

    /** 只读 ZIP 目录（尾部几 KB）定位 payload.bin 数据起点。 */
    static Info probePayload(ReadFn read, long size) throws IOException {
        int tail = (int) Math.min(size, 22 + 65535 + 64);
        byte[] buf = read.read(size - tail, tail);
        int e = -1;
        for (int i = buf.length - 4; i >= 0; i--)
            if (mag(buf, i, 0x50, 0x4B, 0x05, 0x06)) { e = i; break; }
        if (e < 0) throw new IOException("不是标准 ZIP 包（找不到 EOCD）");
        long cdSize = u32(buf, e + 12), cdOff = u32(buf, e + 16);
        if (cdOff == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            int loc = -1;
            for (int i = Math.min(e - 4, buf.length - 4); i >= 0; i--)
                if (mag(buf, i, 0x50, 0x4B, 0x06, 0x07)) { loc = i; break; }
            if (loc < 0) throw new IOException("ZIP64 目录缺失");
            byte[] h = read.read(u64(buf, loc + 8), 56);
            if (!mag(h, 0, 0x50, 0x4B, 0x06, 0x06)) throw new IOException("ZIP64 EOCD 损坏");
            cdSize = u64(h, 40); cdOff = u64(h, 48);
        }
        if (!(cdSize > 0 && cdSize <= 16L * 1024 * 1024))
            throw new IOException("ZIP 中央目录大小异常");
        byte[] cd = read.read(cdOff, (int) cdSize);
        int i = 0;
        while (i + 46 <= cd.length) {
            if (!mag(cd, i, 0x50, 0x4B, 0x01, 0x02)) break;
            int method = (int) u16(cd, i + 10);
            long comp = u32(cd, i + 20), uncomp = u32(cd, i + 24);
            int nlen = (int) u16(cd, i + 28), xlen = (int) u16(cd, i + 30), clen = (int) u16(cd, i + 32);
            long lho = u32(cd, i + 42);
            String fname = new String(cd, i + 46, nlen, "UTF-8");
            int xoff = i + 46 + nlen;
            if (comp == 0xFFFFFFFFL || uncomp == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                int j = xoff, end = xoff + xlen;
                while (j + 4 <= end) {
                    int eid = (int) u16(cd, j), eln = (int) u16(cd, j + 2);
                    if (eid == 1) { // zip64 extended info
                        int p = j + 4;
                        if (uncomp == 0xFFFFFFFFL) { uncomp = readU64Bounded(cd, p); p += 8; }
                        if (comp == 0xFFFFFFFFL) { comp = readU64Bounded(cd, p); p += 8; }
                        if (lho == 0xFFFFFFFFL) { lho = readU64Bounded(cd, p); p += 8; }
                    }
                    j += 4 + eln;
                }
            }
            if (fname.endsWith("payload.bin")) {
                byte[] lh = read.read(lho, (int) Math.min(4096, size - lho));
                if (!mag(lh, 0, 0x50, 0x4B, 0x03, 0x04)) throw new IOException("payload.bin 局部头损坏");
                int nn = (int) u16(lh, 26), xx = (int) u16(lh, 28);
                if (30 + nn + xx > lh.length) lh = read.read(lho, 30 + nn + xx);
                Info in = new Info();
                in.method = method; in.start = lho + 30 + nn + xx; in.comp = comp; in.uncomp = uncomp;
                return in;
            }
            i += 46 + nlen + xlen + clen;
        }
        throw new IOException("包内没有 payload.bin（这是线刷 fastboot 包？请选 Recovery 卡刷包的链接）");
    }

    private static long readU64Bounded(byte[] b, int o) throws IOException {
        if (o + 8 > b.length) throw new IOException("ZIP64 扩展字段越界");
        return u64(b, o);
    }

    /** 按 zip 条目压缩方式读取 payload.bin 前 length 字节。 */
    static byte[] readAt(ReadFn read, long start, int length, int method, long compLen) throws IOException {
        if (method == 0) {
            byte[] data = read.read(start, length);
            if (data.length < length) throw new IOException("payload 数据被截断");
            return data;
        }
        if (method != 8) throw new IOException("不支持的 zip 压缩方式(" + method + ")，需整包下载");
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length, 1 << 20));
        long off = start, left = compLen;
        try {
            byte[] tmp = new byte[Math.min(length, 1 << 16)];
            while (out.size() < length && left > 0) {
                int n = (int) Math.min(65536, left);
                byte[] chunk = read.read(off, n);
                off += chunk.length; left -= chunk.length;
                if (chunk.length == 0) break;
                inf.setInput(chunk);
                try {
                    int got;
                    while ((got = inf.inflate(tmp)) > 0) out.write(tmp, 0, got);
                } catch (java.util.zip.DataFormatException ex) {
                    throw new IOException("解压 payload 头失败: " + ex.getMessage());
                }
            }
        } finally { inf.end(); }
        if (out.size() < length) throw new IOException("payload 头部读取不完整");
        return Arrays.copyOf(out.toByteArray(), length);
    }

    /** 返回 [manifest, manifestSize, sigSize]。 */
    static Object[] manifestMeta(ReadFn read, Info info) throws IOException {
        byte[] head = readAt(read, info.start, 24, info.method, info.comp);
        if (!(head[0] == 'C' && head[1] == 'r' && head[2] == 'A' && head[3] == 'U'))
            throw new IOException("不是有效的 payload.bin（缺 CrAU 魔数）");
        long ver = be64(head, 4), msize = be64(head, 12);
        long sig = be32(head, 20);
        if (ver != 2) throw new IOException("payload 版本不支持: " + ver);
        if (!(msize > 0 && msize <= 64L * 1024 * 1024)) throw new IOException("manifest 大小异常");
        byte[] buf = readAt(read, info.start, (int) (24 + msize), info.method, info.comp);
        byte[] manifest = new byte[(int) msize];
        System.arraycopy(buf, 24, manifest, 0, (int) msize);
        return new Object[]{manifest, msize, sig};
    }

    // ============================================================ manifest
    public static final class PartEntry {
        public final String name; public final long size, download;
        PartEntry(String n, long s, long d) { name = n; size = s; download = d; }
    }

    static final class Op { int type; long doff, dlen; long[][] dst = new long[0][]; byte[] sha; }
    static final class PPart {
        String name = ""; long size; byte[] hash = new byte[0];
        final List<Op> ops = new ArrayList<Op>();
    }
    static final class Deep { int blockSize = 4096; int minor; final List<PPart> parts = new ArrayList<PPart>(); }

    /** 深解析 DeltaArchiveManifest（字段号对齐 AOSP update_metadata.proto）。 */
    static Deep parseDeep(byte[] manifest) throws IOException {
        Deep d = new Deep();
        Pb p = new Pb(manifest);
        while (p.next()) {
            if (p.fno == 3 && p.wt == 0) d.blockSize = (int) p.v;
            else if (p.fno == 12 && p.wt == 0) d.minor = (int) p.v;
            else if (p.fno == 13 && p.wt == 2) {
                PPart pp = new PPart();
                Pb q = new Pb(p.vb);
                while (q.next()) {
                    if (q.fno == 1 && q.wt == 2) pp.name = new String(q.vb, "UTF-8");
                    else if (q.fno == 7 && q.wt == 2) {
                        Pb r = new Pb(q.vb);
                        while (r.next()) {
                            if (r.fno == 1 && r.wt == 0) pp.size = r.v;
                            else if (r.fno == 2 && r.wt == 2) pp.hash = r.vb;
                        }
                    } else if (q.fno == 8 && q.wt == 2) {
                        Op op = new Op();
                        List<long[]> dst = new ArrayList<long[]>();
                        Pb r = new Pb(q.vb);
                        while (r.next()) {
                            if (r.fno == 1 && r.wt == 0) op.type = (int) r.v;
                            else if (r.fno == 2 && r.wt == 0) op.doff = r.v;
                            else if (r.fno == 3 && r.wt == 0) op.dlen = r.v;
                            else if (r.fno == 6 && r.wt == 2) {
                                long s = 0, n = 0;
                                Pb e = new Pb(r.vb);
                                while (e.next()) {
                                    if (e.fno == 1 && e.wt == 0) s = e.v;
                                    else if (e.fno == 2 && e.wt == 0) n = e.v;
                                }
                                dst.add(new long[]{s, n});
                            } else if (r.fno == 8 && r.wt == 2) op.sha = r.vb;
                        }
                        op.dst = dst.toArray(new long[0][]);
                        pp.ops.add(op);
                    }
                }
                if (!pp.name.isEmpty()) d.parts.add(pp);
            }
        }
        return d;
    }

    static String opName(int t) {
        String[] names = {"REPLACE", "REPLACE_BZ", "MOVE", "BSDIFF", "SOURCE_COPY",
            "SOURCE_BSDIFF", "ZERO", "DISCARD", "REPLACE_XZ", "PUFFDIFF",
            "BROTLI_BSDIFF", "ZUCCHINI", "LZ4DIFF_BSDIFF", "LZ4DIFF_PUFFDIFF"};
        return t >= 0 && t < names.length ? names[t] : "type" + t;
    }

    // ============================================================ Range
    /** 精确 Range 小段读取。 */
    public static byte[] range(String url, long start, int len) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(len, 1 << 16));
        Http.streamRange(url, start, len, new Http.StreamCb() {
            public boolean onChunk(byte[] d, int n) throws IOException { bos.write(d, 0, n); return true; }
        });
        byte[] r = bos.toByteArray();
        if (r.length != len) throw new IOException("分段读取不完整 " + r.length + "/" + len);
        return r;
    }

    static ReadFn rangeReader(final String url) {
        return new ReadFn() {
            public byte[] read(long s, int l) throws IOException { return range(url, s, l); }
        };
    }

    // ============================================================ 对外流程
    /** 进度回调；返回 false = 用户取消。done/total 为本次所选分区累计。 */
    public interface Prog { boolean onProgress(long done, long total, long rateBps); }

    public static final class Meta {
        Info info; long base, pkgSize; int blockSize;
        Deep deep;
        public long pkgSize() { return pkgSize; }
        public List<PartEntry> parts() {
            List<PartEntry> out = new ArrayList<PartEntry>();
            for (PPart pp : deep.parts) {
                long dl = 0;
                for (Op op : pp.ops) dl += op.dlen;
                out.add(new PartEntry(pp.name, pp.size, dl));
            }
            return out;
        }
    }

    /** 读取 manifest 并做兼容性检查；返回后续提取所需元信息。 */
    public static Meta prepare(String url) throws IOException {
        long size = Http.probeSize(url);
        if (size <= 0) throw new IOException("无法获取文件大小或服务器不支持 Range");
        ReadFn read = rangeReader(url);
        Meta m = new Meta();
        m.pkgSize = size;
        m.info = probePayload(read, size);
        if (m.info.method != 0) throw new IOException("zip 内 payload.bin 非不压缩存储，无法按需读取");
        Object[] mm = manifestMeta(read, m.info);
        m.base = m.info.start + 24 + (Long) mm[1] + (Long) mm[2];
        m.deep = parseDeep((byte[]) mm[0]);
        if (m.deep.minor != 0)
            throw new IOException("增量差分包(minor_version=" + m.deep.minor + ")，不支持按需提取");
        m.blockSize = m.deep.blockSize == 0 ? 4096 : m.deep.blockSize;
        return m;
    }

    static void checkOps(PPart p) throws IOException {
        for (Op op : p.ops) {
            int t = op.type;
            if (t != 0 && t != 1 && t != 6 && t != 7 && t != 8)
                throw new IOException(p.name + " 含差分/不支持操作（" + opName(t) + "），无法极速提取");
        }
    }

    private static final long OP_MEM_CAP = 96L * 1024 * 1024;

    /** 极速提取单个分区 → dir/name.img。返回文件路径。 */
    public static String extractOne(String url, Meta m, String name, File dir, Prog prog) throws IOException {
        PPart p = null;
        long total = 0;
        for (PPart q : m.deep.parts) {
            if (q.name.equals(name)) { p = q; break; }
        }
        if (p == null) throw new IOException("包内没有分区: " + name);
        checkOps(p);
        for (Op op : p.ops) total += op.dlen;
        dir.mkdirs();
        File out = new File(dir, p.name + ".img");
        File tmp = new File(out.getAbsolutePath() + ".part");
        long[] got = {0};
        long t0 = System.currentTimeMillis();
        final long totalF = total;
        RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
        boolean ok = false;
        boolean keepPart = false;
        try {
            for (Op op : p.ops) {
                int t = op.type;
                long expectDst = 0;
                for (long[] e : op.dst) expectDst += e[1] * m.blockSize;
                if (t == 6 || t == 7) continue; // ZERO/DISCARD：洞即零
                if (op.dlen == 0) {
                    if (expectDst != 0) throw new IOException(p.name + ": op " + t + " 数据长度为 0");
                    continue;
                }
                if (op.dlen > OP_MEM_CAP)
                    throw new IOException(p.name + ": 单个操作数据 " + op.dlen + " 字节过大，请下整包");
                // 分块下载（4MB 粒度：可取消 + 进度 + 流式 sha256）
                MessageDigest h = sha256();
                ByteArrayOutputStream raw = new ByteArrayOutputStream((int) Math.min(op.dlen, 1 << 22));
                long left = op.dlen, off = m.base + op.doff;
                while (left > 0) {
                    long cn = Math.min(4L * 1024 * 1024, left);
                    byte[] chunk = range(url, off, (int) cn);
                    h.update(chunk);
                    raw.write(chunk);
                    off += cn; left -= cn;
                    got[0] += cn;
                    long now = System.currentTimeMillis();
                    long rate = got[0] * 1000 / Math.max(now - t0, 1);
                    if (!prog.onProgress(got[0], totalF, rate)) throw new IOException("已取消");
                }
                byte[] data = raw.toByteArray();
                if (op.sha != null && !Arrays.equals(h.digest(), op.sha))
                    throw new IOException(p.name + ": 操作数据 sha256 校验失败");
                byte[] plain;
                if (t == 1 || t == 8) {
                    plain = decompress(t, data, p.name);
                } else plain = data;
                ExtentWriter w = new ExtentWriter(raf, op.dst, m.blockSize);
                w.write(plain);
                if (w.written != expectDst)
                    throw new IOException(p.name + ": 解出 " + w.written + " 字节，dst_extents 期望 " + expectDst);
            }
            if (p.size > 0) raf.setLength(p.size);
            if (p.hash.length > 0) {
                MessageDigest whole = sha256();
                raf.seek(0);
                byte[] buf = new byte[1 << 20];
                int r;
                while ((r = raf.read(buf)) > 0) whole.update(buf, 0, r);
                if (!Arrays.equals(whole.digest(), p.hash)) {
                    keepPart = true; // 与 Python 一致：校验失败保留现场
                    throw new IOException(p.name + ": 整分区 sha256 与 manifest 不符（已保留 .part 文件）");
                }
            }
            ok = true;
        } finally {
            raf.close();
            if (!ok && !keepPart) tmp.delete();
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new IOException("重命名输出文件失败");
        return out.getAbsolutePath();
    }

    static byte[] decompress(int type, byte[] data, String part) throws IOException {
        InputStream in = new ByteArrayInputStream(data);
        try {
            if (type == 1) in = new BZip2CompressorInputStream(in);
            else if (type == 8) in = new XZInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(data.length, 1 << 20));
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IOException(part + " 解压失败: " + ex.getMessage());
        } finally { in.close(); }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 把线性字节流按 dst_extents 写入（洞保持稀疏零）。 */
    static final class ExtentWriter {
        private final RandomAccessFile fh; private final long[][] exts; private final int bs;
        private int i = 0; private long off = 0; long written = 0;
        ExtentWriter(RandomAccessFile fh, long[][] exts, int bs) { this.fh = fh; this.exts = exts; this.bs = bs; }
        void write(byte[] data) throws IOException {
            int p = 0, n = data.length;
            while (p < n) {
                if (i >= exts.length) throw new IOException("数据量超出 dst_extents 预期");
                long s = exts[i][0], cap = exts[i][1] * bs;
                int take = (int) Math.min(cap - off, n - p);
                fh.seek(s * bs + off);
                fh.write(data, p, take);
                off += take; written += take; p += take;
                if (off >= cap) { i++; off = 0; }
            }
        }
    }
}
