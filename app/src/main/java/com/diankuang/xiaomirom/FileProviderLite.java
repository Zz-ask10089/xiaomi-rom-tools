package com.diankuang.xiaomirom;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 迷你 FileProvider（不依赖 AndroidX）：共享应用专属 Download 目录
 * （含 payload_extracted 子目录）里的 CSV / .img 文件。
 */
public class FileProviderLite extends ContentProvider {

    @Override
    public boolean onCreate() { return true; }

    private File[] bases() {
        File d1 = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File d2 = getContext().getFilesDir();
        File d3 = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return d1 == null ? new File[]{d2, d3} : new File[]{d1, d2, d3};
    }

    /** 只允许应用专属目录内的相对路径（拒绝 .. 逃逸）。 */
    private File resolve(String rel) {
        if (rel == null || rel.isEmpty()) return null;
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.contains("..")) return null;
        for (File dir : bases()) {
            File f = new File(dir, rel);
            try {
                if (f.isFile() && f.getCanonicalPath()
                        .startsWith(dir.getCanonicalPath() + File.separator)) return f;
            } catch (Exception ignored) { }
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri.getPath());
        if (f == null) throw new FileNotFoundException("gone: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String s, String[] sa, String so) {
        return query(uri, proj, s, sa, so, null);
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String s, String[] sa, String so,
                        CancellationSignal cs) {
        File f = resolve(uri.getPath());
        MatrixCursor c = new MatrixCursor(new String[]{
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE});
        if (f != null) c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) { return "application/octet-stream"; }

    @Override
    public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override
    public int delete(Uri uri, String s, String[] sa) { return 0; }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] sa) { return 0; }
}
