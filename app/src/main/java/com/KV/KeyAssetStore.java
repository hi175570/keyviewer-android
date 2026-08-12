package com.KV;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/** Copies user-selected key artwork/fonts into private storage and validates them. */
final class KeyAssetStore {
    static final String TYPE_IMAGE = "image";
    static final String TYPE_FONT = "font";
    static final long MAX_ASSET_BYTES = 12L * 1024L * 1024L;

    private KeyAssetStore() {}

    static String importAsset(Context context, Uri uri, String type) throws IOException {
        if (uri == null) throw new IOException("No asset selected");
        String extension = extensionOf(displayName(context, uri));
        File directory = new File(context.getFilesDir(), "key_assets");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create key asset directory");
        }
        File destination = new File(directory, UUID.randomUUID() + extension);
        long total = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Unable to open selected asset");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_ASSET_BYTES) throw new IOException("Asset exceeds 12 MiB limit");
                output.write(buffer, 0, count);
            }
        } catch (IOException e) {
            // This path was created by this method and is safe to remove after a failed copy.
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw e;
        }

        try {
            validateAsset(context, destination, type);
        } catch (RuntimeException | IOException e) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Invalid " + type + " asset", e);
        }
        return destination.getName();
    }

    static String importEmbeddedAsset(Context context, byte[] data, String extension, String type)
            throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_ASSET_BYTES) {
            throw new IOException("Embedded asset has an invalid size");
        }
        if (!TYPE_FONT.equals(type) && !TYPE_IMAGE.equals(type)) {
            throw new IOException("Unknown embedded asset type");
        }
        String safeExtension = safeExtension(extension);
        File directory = new File(context.getFilesDir(), "key_assets");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create key asset directory");
        }
        File destination = new File(directory, UUID.randomUUID() + safeExtension);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(data);
            output.flush();
            output.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw e;
        }
        try {
            validateAsset(context, destination, type);
        } catch (RuntimeException | IOException e) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Invalid embedded " + type + " asset", e);
        }
        return destination.getName();
    }

    static String extensionOfStoredName(String storedName) {
        if (storedName == null) return ".bin";
        int dot = storedName.lastIndexOf('.');
        return dot < 0 ? ".bin" : safeExtension(storedName.substring(dot));
    }

    static File resolve(Context context, String storedName) {
        if (storedName == null || !storedName.matches("[A-Za-z0-9._-]+")) return null;
        File file = new File(new File(context.getFilesDir(), "key_assets"), storedName);
        return file.isFile() ? file : null;
    }

    static Drawable loadDrawable(Context context, String storedName) throws IOException {
        File file = resolve(context, storedName);
        if (file == null) return null;
        if (storedName.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            try (FileInputStream input = new FileInputStream(file)) {
                return ExternalVectorDrawable.create(context, input);
            } catch (Exception e) {
                throw new IOException("Invalid VectorDrawable XML", e);
            }
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return Drawable.createFromStream(input, storedName);
        }
    }

    private static void validateAsset(Context context, File file, String type) throws IOException {
        if (TYPE_FONT.equals(type)) {
            Typeface.createFromFile(file);
        } else if (TYPE_IMAGE.equals(type)) {
            if (loadDrawable(context, file.getName()) == null) {
                throw new IOException(
                        "Unsupported image; use PNG/JPEG/WebP/GIF or VectorDrawable XML");
            }
        } else {
            throw new IOException("Unknown asset type");
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment();
    }

    private static String extensionOf(String name) {
        if (name == null) return ".bin";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return ".bin";
        return safeExtension(name.substring(dot));
    }

    private static String safeExtension(String extension) {
        if (extension == null) return ".bin";
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.matches("\\.[a-z0-9]{1,8}") ? normalized : ".bin";
    }
}
