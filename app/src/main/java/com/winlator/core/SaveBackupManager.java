package com.winlator.core;

import android.os.Environment;

import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Gerencia backup e restauração de saves/configs de um container Wine.
 *
 * Fontes varridas:
 *   1. drive_c/users/xuser/  — pastas canônicas + filtro por extensão/keyword
 *   2. drive_c/ProgramData/  — apenas pastas de launchers/publishers conhecidos
 *                               + filtro de save/profile nas demais
 *
 * Limite: arquivos > 15 MB são sempre ignorados.
 * Opera em background thread — nunca chame da Main Thread.
 */
public class SaveBackupManager {

    private static final long MAX_FILE_SIZE_BYTES = 15L * 1024 * 1024;

    private static final Set<String> SAVE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "sav", "save", "ini", "cfg", "config", "dat", "xml",
        "sl2", "ess", "bak", "json", "db", "sqlite", "profile",
        "prf", "set", "options", "prefs"
    ));

    private static final Set<String> SAVE_KEYWORDS = new HashSet<>(Arrays.asList(
        "save", "saves", "savegame", "savegames", "savedgame", "savedgames",
        "profile", "profiles", "config", "configs", "configuration",
        "user", "userdata", "appdata", "documents", "my documents",
        "my games", "mygames", "settings", "options", "prefs",
        "preferences", "slots", "checkpoint", "quicksave", "autosave",
        "player", "players", "slot"
    ));

    /**
     * Pastas de launchers/publishers conhecidos dentro de ProgramData/.
     * Se o nome da pasta contiver qualquer uma dessas strings, ela é varrida
     * por completo (respeitando extensão + limite 15 MB).
     */
    private static final Set<String> PROGRAMDATA_LAUNCHERS = new HashSet<>(Arrays.asList(
        // Rockstar / GTA
        "rockstar", "socialclub", "social club",
        // Ubisoft
        "ubisoft", "orbit",
        // EA / Origin
        "ea", "origin", "electronic arts",
        // Steam / Valve
        "steam", "valve",
        // Epic
        "epic", "epicgames", "epic games",
        // GOG
        "gog", "galaxy",
        // Bethesda
        "bethesda",
        // CD Projekt
        "cd projekt", "cdprojekt", "gog.com",
        // 2K / Take-Two
        "2k", "take-two", "taketwo",
        // Square Enix
        "square enix", "squareenix",
        // Activision / Blizzard
        "activision", "blizzard",
        // Outros publishers comuns
        "bandai", "namco", "capcom", "konami", "sega",
        "thq", "nordic", "deep silver", "focus", "paradox",
        "devolver", "coffeestain", "coffee stain",
        // Genéricos de save em ProgramData
        "save", "saves", "profile", "profiles", "userdata"
    ));

    private static final String[] USER_SCAN_SUBDIRS = {
        "AppData/Roaming",
        "AppData/Local",
        "Documents",
        "My Documents",
        "Saved Games",
        "Desktop"
    };

    private static final String USERS_PREFIX       = "users";
    private static final String PROGRAMDATA_PREFIX = "ProgramData";

    private static final String BACKUP_ROOT =
        Environment.getExternalStorageDirectory().getAbsolutePath()
        + "/Winlator_NEO/Backups";

    // ─────────────────────────────────────────────────────────────────────────

    public static File getBackupDir(Container container) {
        String safeName = container.getName().replaceAll("[^a-zA-Z0-9_\\-. ]", "_");
        return new File(BACKUP_ROOT, safeName);
    }

    public static int backup(Container container) {
        File backupDir = getBackupDir(container);
        if (!backupDir.exists() && !backupDir.mkdirs()) return -1;

        int[] count = {0};

        // ── Fonte 1: users/xuser/ ─────────────────────────────────────────
        File userDir = container.getUserDir();
        if (userDir.isDirectory()) {
            File dstUsers = new File(backupDir, USERS_PREFIX);

            for (String sub : USER_SCAN_SUBDIRS) {
                File srcSub = new File(userDir, sub);
                if (srcSub.isDirectory()) {
                    scanAndCopy(srcSub, new File(dstUsers, sub), userDir, dstUsers, count);
                }
            }

            File[] rootFiles = userDir.listFiles();
            if (rootFiles != null) {
                for (File f : rootFiles) {
                    if (f.isFile() && isRelevantFile(f, f.getName().toLowerCase())) {
                        if (copyFile(f, new File(dstUsers, f.getName()))) count[0]++;
                    }
                }
            }
        }

        // ── Fonte 2: ProgramData/ ─────────────────────────────────────────
        File programDataDir = new File(container.getRootDir(), ".wine/drive_c/ProgramData");
        if (programDataDir.isDirectory()) {
            backupProgramData(programDataDir, new File(backupDir, PROGRAMDATA_PREFIX), count);
        }

        return count[0];
    }

    public static int restore(Container container) {
        File backupDir = getBackupDir(container);
        if (!backupDir.isDirectory()) return -1;

        int[] count = {0};

        // Restaura users/
        File backupUsers = new File(backupDir, USERS_PREFIX);
        File userDir = container.getUserDir();
        if (backupUsers.isDirectory() && userDir.isDirectory()) {
            restoreRecursive(backupUsers, userDir, count);
        }

        // Restaura ProgramData/
        File backupPD = new File(backupDir, PROGRAMDATA_PREFIX);
        File programDataDir = new File(container.getRootDir(), ".wine/drive_c/ProgramData");
        if (backupPD.isDirectory() && programDataDir.isDirectory()) {
            restoreRecursive(backupPD, programDataDir, count);
        }

        return count[0];
    }

    public static boolean hasBackup(Container container) {
        File backupDir = getBackupDir(container);
        return backupDir.isDirectory() && !isEmpty(backupDir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lógica de ProgramData
    // ─────────────────────────────────────────────────────────────────────────

    private static void backupProgramData(File programDataDir, File dstProgramData, int[] count) {
        File[] topDirs = programDataDir.listFiles();
        if (topDirs == null) return;

        for (File dir : topDirs) {
            if (FileUtils.isSymlink(dir)) continue;

            if (dir.isDirectory()) {
                String nameLower = dir.getName().toLowerCase();
                if (isKnownLauncher(nameLower) || pathMatchesKeyword(nameLower)) {
                    // Launcher conhecido: varre tudo dentro com filtro extensão + tamanho
                    File dst = new File(dstProgramData, dir.getName());
                    scanAndCopy(dir, dst, dir, dst, count);
                }
                else {
                    // Pasta desconhecida: pega só arquivos relevantes do 1º nível
                    File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        if (f.isFile() && isRelevantFile(f, f.getName().toLowerCase())) {
                            File dst = new File(dstProgramData, dir.getName() + "/" + f.getName());
                            if (copyFile(f, dst)) count[0]++;
                        }
                    }
                }
            }
            else if (dir.isFile() && isRelevantFile(dir, dir.getName().toLowerCase())) {
                if (copyFile(dir, new File(dstProgramData, dir.getName()))) count[0]++;
            }
        }
    }

    private static boolean isKnownLauncher(String nameLower) {
        for (String launcher : PROGRAMDATA_LAUNCHERS) {
            if (nameLower.contains(launcher)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Varredura genérica
    // ─────────────────────────────────────────────────────────────────────────

    private static void scanAndCopy(File srcDir, File dstDir, File srcBase, File dstBase, int[] count) {
        if (FileUtils.isSymlink(srcDir)) return;
        File[] files = srcDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (FileUtils.isSymlink(f)) continue;
            String relPath = getRelativePath(f, srcBase).toLowerCase();

            if (f.isDirectory()) {
                if (pathMatchesKeyword(relPath) || isAlwaysScanDir(f.getName())) {
                    scanAndCopy(f, new File(dstBase, getRelativePath(f, srcBase)), srcBase, dstBase, count);
                }
                else {
                    scanFilesOnly(f, srcBase, dstBase, count);
                }
            }
            else if (isRelevantFile(f, relPath)) {
                if (copyFile(f, new File(dstBase, getRelativePath(f, srcBase)))) count[0]++;
            }
        }
    }

    private static void scanFilesOnly(File srcDir, File srcBase, File dstBase, int[] count) {
        if (FileUtils.isSymlink(srcDir)) return;
        File[] files = srcDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (FileUtils.isSymlink(f)) continue;
            String relPath = getRelativePath(f, srcBase).toLowerCase();

            if (f.isDirectory() && pathMatchesKeyword(relPath)) {
                scanAndCopy(f, new File(dstBase, getRelativePath(f, srcBase)), srcBase, dstBase, count);
            }
            else if (f.isFile() && isRelevantFile(f, relPath)) {
                if (copyFile(f, new File(dstBase, getRelativePath(f, srcBase)))) count[0]++;
            }
        }
    }

    private static void restoreRecursive(File srcDir, File dstDir, int[] count) {
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            File dst = new File(dstDir, f.getName());
            if (f.isDirectory()) {
                if (!dst.exists()) dst.mkdirs();
                restoreRecursive(f, dst, count);
            }
            else if (copyFile(f, dst)) count[0]++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isRelevantFile(File file, String relPathLower) {
        if (file.length() > MAX_FILE_SIZE_BYTES) return false;
        String ext = getExtension(file.getName()).toLowerCase();
        // Sem extensão (ex: SGTA50003) — aceita se < 15MB (dentro de pasta de launcher já filtrada)
        if (ext.isEmpty()) return true;
        return SAVE_EXTENSIONS.contains(ext) || pathMatchesKeyword(relPathLower);
    }

    private static boolean pathMatchesKeyword(String relPathLower) {
        for (String kw : SAVE_KEYWORDS) {
            if (relPathLower.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isAlwaysScanDir(String dirName) {
        String lower = dirName.toLowerCase();
        return lower.equals("appdata") || lower.equals("documents")
            || lower.equals("saved games") || lower.equals("my documents")
            || lower.equals("desktop");
    }

    private static boolean copyFile(File src, File dst) {
        if (!src.isFile() || src.length() > MAX_FILE_SIZE_BYTES) return false;
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            FileChannel in  = new FileInputStream(src).getChannel();
            FileChannel out = new FileOutputStream(dst).getChannel();
            in.transferTo(0, in.size(), out);
            in.close();
            out.close();
            return true;
        }
        catch (IOException e) { return false; }
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1)
            ? filename.substring(dot + 1) : "";
    }

    private static String getRelativePath(File file, File base) {
        String filePath = file.getAbsolutePath();
        String basePath = base.getAbsolutePath();
        if (filePath.startsWith(basePath)) {
            String rel = filePath.substring(basePath.length());
            return rel.startsWith("/") ? rel.substring(1) : rel;
        }
        return file.getName();
    }

    private static boolean isEmpty(File dir) {
        String[] list = dir.list();
        return list == null || list.length == 0;
    }
}
