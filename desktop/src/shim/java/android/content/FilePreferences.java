package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** [SharedPreferences] kept in a properties file, written whole on every change. */
final class FilePreferences implements SharedPreferences {
    private final File file;
    private final boolean privateFile;
    private final Properties values = new Properties();

    FilePreferences(File file, boolean privateFile) {
        this.file = file;
        this.privateFile = privateFile;
        if (file.exists()) {
            try {
                if (privateFile) restrictAccess(file.toPath());
                try (InputStream in = new FileInputStream(file)) {
                    values.load(in);
                }
            } catch (IOException ignored) {
                // An unreadable file reads as empty, as a fresh install would.
            }
        }
    }

    private synchronized String raw(String key) {
        return values.getProperty(key);
    }

    @Override public String getString(String key, String defValue) {
        String v = raw(key);
        return v != null ? v : defValue;
    }

    @Override public int getInt(String key, int defValue) {
        try { String v = raw(key); return v != null ? Integer.parseInt(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public long getLong(String key, long defValue) {
        try { String v = raw(key); return v != null ? Long.parseLong(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public float getFloat(String key, float defValue) {
        try { String v = raw(key); return v != null ? Float.parseFloat(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public boolean getBoolean(String key, boolean defValue) {
        String v = raw(key);
        return v != null ? Boolean.parseBoolean(v) : defValue;
    }

    @Override public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, String> puts = new HashMap<>();
            private final java.util.Set<String> removes = new java.util.HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) {
                if (value == null) return remove(key);
                puts.put(key, value);
                removes.remove(key);
                return this;
            }
            @Override public Editor putInt(String key, int value) { return putString(key, Integer.toString(value)); }
            @Override public Editor putLong(String key, long value) { return putString(key, Long.toString(value)); }
            @Override public Editor putFloat(String key, float value) { return putString(key, Float.toString(value)); }
            @Override public Editor putBoolean(String key, boolean value) { return putString(key, Boolean.toString(value)); }
            @Override public Editor remove(String key) { removes.add(key); puts.remove(key); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override public boolean commit() {
                synchronized (FilePreferences.this) {
                    if (clear) values.clear();
                    for (String k : removes) values.remove(k);
                    values.putAll(puts);
                    return save();
                }
            }

            @Override public void apply() { commit(); }
        };
    }

    private boolean save() {
        file.getParentFile().mkdirs();
        if (privateFile) return savePrivate();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            values.store(out, null);
        } catch (IOException e) {
            return false;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            return tmp.renameTo(file);
        }
        return true;
    }

    private static void restrictAccess(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular preferences file");
        }
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private boolean savePrivate() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(file.getParentFile().toPath(), "account-", ".tmp");
            restrictAccess(tmp);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                values.store(out, null);
            }
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }
}
