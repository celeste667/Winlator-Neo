package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.Spinner;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public abstract class GeneralComponents {
    public enum InstallMode {DOWNLOAD, FILE, BOTH}
    private static final String INSTALLABLE_COMPONENTS_URL =
        "https://raw.githubusercontent.com/charlotteAsy/Components/main/installable_components/%s";

    /**
     * Mapa global: displayName → fileName real no disco
     * Chave composta: "TYPE::displayName"
     */
    private static final Map<String, String> displayToFileMap = new LinkedHashMap<>();

    private static String mapKey(Type type, String displayName) {
        return type.name() + "::" + displayName;
    }

    private static void registerDisplayName(Type type, String filename, String displayName) {
        displayToFileMap.put(mapKey(type, displayName), filename);
    }

    public static String resolveFilename(Type type, String displayName) {
        String mapped = displayToFileMap.get(mapKey(type, displayName));
        if (mapped != null) return mapped;

        if (type == Type.TURNIP) {
            String[] candidates = {
                displayName + ".tzst",
                displayName + ".zip",
                type.lowerName() + "-" + displayName + ".tzst",
                type.lowerName() + "-" + displayName + ".zip",
            };
            return candidates[0];
        }

        return type.lowerName() + "-" + displayName + ".tzst";
    }

    // ─────────────────────────────────────────────────────────────
    public enum Type {
        BOX64, TURNIP, DXVK, VKD3D, WINED3D, SOUNDFONT, ADRENOTOOLS_DRIVER;

        String lowerName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

        String title() {
            switch (this) {
                case BOX64:              return "Box64";
                case TURNIP:             return "Turnip";
                case DXVK:               return "DXVK";
                case VKD3D:              return "VKD3D";
                case WINED3D:            return "WineD3D";
                case SOUNDFONT:          return "SoundFont";
                case ADRENOTOOLS_DRIVER: return "Adrenotools Driver";
                default:                 return "";
            }
        }

        String assetFolder() {
            switch (this) {
                case BOX64:    return "box64";
                case TURNIP:   return "graphics_driver";
                case WINED3D:
                case DXVK:
                case VKD3D:    return "dxwrapper";
                case SOUNDFONT: return "soundfont";
                default:       return "";
            }
        }

        File getSource(Context context, String identifier) {
            File componentDir = getComponentDir(this, context);

            switch (this) {
                case SOUNDFONT:
                    return new File(componentDir, identifier + ".sf2");

                case ADRENOTOOLS_DRIVER:
                    return new File(componentDir, identifier);

                case TURNIP: {
                    String mappedFile = displayToFileMap.get(mapKey(this, identifier));
                    if (mappedFile != null) {
                        File f = new File(componentDir, mappedFile);
                        if (f.exists()) return f;
                    }
                    String[] candidates = {
                        identifier + ".tzst",
                        identifier + ".zip",
                        lowerName() + "-" + identifier + ".tzst",
                        lowerName() + "-" + identifier + ".zip",
                    };
                    for (String candidate : candidates) {
                        File f = new File(componentDir, candidate);
                        if (f.exists()) return f;
                    }
                    return new File(componentDir, lowerName() + "-" + identifier + ".tzst");
                }

                default:
                    return new File(componentDir, lowerName() + "-" + identifier + ".tzst");
            }
        }

        public File getDestination(Context context) {
            File rootDir = RootFS.find(context).getRootDir();
            switch (this) {
                case DXVK:
                case VKD3D:
                case WINED3D:
                    return new File(rootDir,
                        RootFS.WINEPREFIX + "/drive_c/windows");
                case SOUNDFONT:
                    File destination = new File(context.getCacheDir(), "soundfont");
                    if (!destination.isDirectory()) destination.mkdirs();
                    return destination;
                default:
                    return rootDir;
            }
        }

        InstallMode getInstallMode() {
            if (this == SOUNDFONT || this == ADRENOTOOLS_DRIVER) {
                return InstallMode.FILE;
            } else if (this == WINED3D || this == DXVK
                    || this == VKD3D) {
                return InstallMode.BOTH;
            }
            return InstallMode.DOWNLOAD;
        }

        boolean isVersioned() {
            return this == BOX64 || this == TURNIP
                || this == DXVK  || this == VKD3D || this == WINED3D;
        }
    }

    // ─────────────────────────────────────────────────────────────
    public static ArrayList<String> getBuiltinComponentNames(Type type) {
        String[] items;
        switch (type) {
            case BOX64:    items = new String[]{DefaultVersion.BOX64};   break;
            case TURNIP:   items = new String[]{DefaultVersion.TURNIP};  break;
            case DXVK:     items = new String[]{DefaultVersion.MINOR_DXVK,
                                                DefaultVersion.MAJOR_DXVK}; break;
            case VKD3D:    items = new String[]{DefaultVersion.VKD3D};   break;
            case WINED3D:  items = new String[]{DefaultVersion.WINED3D}; break;
            case SOUNDFONT: items = new String[]{DefaultVersion.SOUNDFONT}; break;
            case ADRENOTOOLS_DRIVER: items = new String[]{"System"};     break;
            default:       items = new String[0];
        }
        return new ArrayList<>(Arrays.asList(items));
    }

    public static File getComponentDir(Type type, Context context) {
        File file = new File(context.getFilesDir(),
            "/installed_components/" + type.lowerName());
        if (!file.isDirectory()) file.mkdirs();
        return file;
    }

    public static ArrayList<String> getInstalledComponentNames(
            Type type, Context context) {

        File componentDir = getComponentDir(type, context);
        ArrayList<String> result = new ArrayList<>();

        String[] names;
        if (componentDir.isDirectory()
                && (names = componentDir.list()) != null) {
            for (String filename : names) {
                String displayName = parseDisplayText(type, filename);
                registerDisplayName(type, filename, displayName);
                result.add(displayName);
            }
        }
        return result;
    }

    public static boolean isBuiltinComponent(Type type, String identifier) {
        for (String name : getBuiltinComponentNames(type)) {
            if (name.equalsIgnoreCase(identifier)) return true;
        }
        return false;
    }

    public static String getDefinitivePath(
            Type type, Context context, String identifier) {
        if (identifier == null || identifier.isEmpty()) return null;

        if (type == Type.SOUNDFONT && isBuiltinComponent(type, identifier)) {
            File destination = type.getDestination(context);
            FileUtils.clear(destination);
            String filename = identifier + ".sf2";
            destination = new File(destination, filename);
            FileUtils.copy(context, type.assetFolder() + "/" + filename, destination);
            return destination.getPath();
        } else if (type == Type.ADRENOTOOLS_DRIVER) {
            if (isBuiltinComponent(type, identifier)) return null;
            File source = type.getSource(context, identifier);
            File[] manifestFiles = source.listFiles(
                (file, name) -> name.endsWith(".json"));
            if (manifestFiles != null) {
                try {
                    JSONObject manifestJSONObject = new JSONObject(
                        FileUtils.readString(manifestFiles[0]));
                    String libraryName =
                        manifestJSONObject.optString("libraryName", "");
                    File libraryFile = new File(source, libraryName);
                    return libraryFile.isFile() ? libraryFile.getPath() : null;
                } catch (JSONException e) {
                    return null;
                }
            }
        }

        return type.getSource(context, identifier).getPath();
    }

    // ─────────────────────────────────────────────────────────────
    public static void extractFile(
            Type type, Context context,
            String identifier, String defaultVersion) {
        extractFile(type, context, identifier, defaultVersion, null);
    }

    public static void extractFile(
            Type type, Context context, String identifier,
            String defaultVersion,
            TarCompressorUtils.OnExtractFileListener onExtractFileListener) {

        File destination = type.getDestination(context);

        if (isBuiltinComponent(type, identifier)) {
            String sourcePath = type.assetFolder() + "/"
                + type.lowerName() + "-" + identifier + ".tzst";
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                context, sourcePath, destination, onExtractFileListener);
        } else {
            File source = type.getSource(context, identifier);
            boolean success = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                source, destination, onExtractFileListener);

            if (!success) {
                String sourcePath = type.assetFolder() + "/"
                    + type.lowerName() + "-" + defaultVersion + ".tzst";
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                    context, sourcePath, destination, onExtractFileListener);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    private static String parseDisplayText(Type type, String filename) {
        String result = filename;

        if (type == Type.TURNIP) {
            if (result.endsWith(".tzst")) {
                result = result.substring(0, result.length() - 5);
            } else if (result.endsWith(".zip")) {
                result = result.substring(0, result.length() - 4);
            }
            String prefix = type.lowerName() + "-";
            if (result.startsWith(prefix)) {
                result = result.substring(prefix.length());
            }
            return result;
        }

        return filename
            .replace(type.lowerName() + "-", "")
            .replace(".tzst", "")
            .replace(".sf2", "")
            .replace(".zip", "")
            .replace(".wcp", "");
    }

    // ─────────────────────────────────────────────────────────────
    /**
     * CORRIGIDO: Suporte a .zip e .wcp além do padrão .tzst
     * - displayName definido antes de qualquer operação
     * - try-catch na extração para evitar crash
     * - fallback para .tzst se não for .zip/.wcp
     */
    private static void downloadComponentFile(
            final Type type, final String filename,
            final Spinner spinner, final String defaultItem) {

        final Activity activity = (Activity) spinner.getContext();
        File destination = new File(getComponentDir(type, activity), filename);
        if (destination.isFile()) destination.delete();

        HttpUtils.download(activity,
            String.format(INSTALLABLE_COMPONENTS_URL,
                type.lowerName() + "/" + filename),
            destination,
            (success) -> {
                if (!success) {
                    AppUtils.showToast(activity, R.string.a_network_error_occurred);
                    return;
                }

                // ✅ displayName definido ANTES de qualquer operação
                final String displayName = parseDisplayText(type, filename);
                final String lowerFilename = filename.toLowerCase(Locale.ENGLISH);
                final String lowerTypeName = type.lowerName();

                // ✅ Verifica se é um tipo que precisa de extração
                // (não é turnip — drivers de GPU têm fluxo próprio)
                boolean needsExtraction = !lowerTypeName.contains("turnip")
                    && (lowerFilename.endsWith(".zip")
                        || lowerFilename.endsWith(".wcp"));

                if (needsExtraction) {
                    // ✅ try-catch para evitar crash em ZIP corrompido
                    try {
                        File componentDir = getComponentDir(type, activity);
                        File zipFile = new File(componentDir, filename);

                        boolean extracted = ZipUtils.extract(zipFile, componentDir);

                        if (extracted) {
                            // Remove o .zip/.wcp após extração bem-sucedida
                            if (zipFile.isFile()) zipFile.delete();
                            registerDisplayName(type, filename, displayName);
                            loadSpinner(type, spinner, displayName, defaultItem);
                        } else {
                            AppUtils.showToast(activity,
                                R.string.a_network_error_occurred);
                        }
                    } catch (Exception e) {
                        // ✅ ZIP corrompido ou arquivo não encontrado
                        // exibe Toast em vez de fechar o app
                        AppUtils.showToast(activity,
                            R.string.a_network_error_occurred);
                    }
                } else {
                    // ✅ Fluxo original para .tzst e drivers de GPU (turnip)
                    registerDisplayName(type, filename, displayName);
                    loadSpinner(type, spinner, displayName, defaultItem);
                }
            });
    }

    // ─────────────────────────────────────────────────────────────
    private static void installFromPackagedFile(
            Context context, TarCompressorUtils.Type compressedType,
            final Type type, File originFile,
            String identifier, JSONArray filesJSONArray) throws JSONException {

        File componentDir = getComponentDir(type, context);
        File tempDir = new File(componentDir, type.lowerName() + "-" + identifier);
        if (tempDir.isDirectory()) FileUtils.delete(tempDir);
        tempDir.mkdirs();

        for (int i = 0; i < filesJSONArray.length(); i++) {
            JSONObject fileJSONObject = filesJSONArray.getJSONObject(i);
            String target = fileJSONObject.getString("target");
            File file = null;

            if (target.contains("system32")) {
                file = new File(tempDir,
                    "system32/" + FileUtils.getName(target));
            } else if (target.contains("syswow64")) {
                file = new File(tempDir,
                    "syswow64/" + FileUtils.getName(target));
            }

            if (file != null) {
                file.getParentFile().mkdirs();
                final String source = fileJSONObject.getString("source");
                TarCompressorUtils.extract(compressedType, originFile, tempDir,
                    (dest, size) ->
                        dest.getPath().endsWith(source) ? dest : null);
            }
        }

        String outFilename = type.lowerName() + "-" + identifier + ".tzst";
        File outDestination = new File(componentDir, outFilename);
        TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD,
            new File(tempDir, "/."), outDestination,
            MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);
        FileUtils.delete(tempDir);
    }

    // ─────────────────────────────────────────────────────────────
    private static void openFileForInstall(
            final MainActivity activity, final Type type,
            final Spinner spinner, final String defaultItem) {

        activity.setOpenFileCallback((uri) -> {
            String path = FileUtils.getFilePathFromUri(uri);
            if (path == null) return;

            try {
                File source = new File(path);
                switch (type) {
                    case SOUNDFONT: {
                        String filename = FileUtils.getName(path);
                        File destination = new File(
                            getComponentDir(type, activity), filename);
                        if (destination.isFile()) FileUtils.delete(destination);
                        if (FileUtils.copy(source, destination)) {
                            String displayName = parseDisplayText(type, filename);
                            registerDisplayName(type, filename, displayName);
                            loadSpinner(type, spinner, displayName, defaultItem);
                        }
                        break;
                    }
                    case ADRENOTOOLS_DRIVER: {
                        byte[] manifestData = ZipUtils.read(source, "*.json");
                        if (manifestData != null) {
                            JSONObject manifestJSONObject =
                                new JSONObject(new String(manifestData));
                            String filename = manifestJSONObject.optString("name",
                                manifestJSONObject.optString("libraryName", ""));
                            File destination = new File(
                                getComponentDir(type, activity), filename);
                            if (destination.isDirectory())
                                FileUtils.delete(destination);
                            destination.mkdirs();
                            if (ZipUtils.extract(source, destination)) {
                                registerDisplayName(type, filename, filename);
                                loadSpinner(type, spinner, filename, defaultItem);
                            }
                        }
                        break;
                    }
                    
                    default: {
                        TarCompressorUtils.Type compressedType =
                            TarCompressorUtils.Type.ZSTD;
                        byte[] manifestData = TarCompressorUtils.read(
                            compressedType, source, "*.json");
                        if (manifestData == null) {
                            manifestData = TarCompressorUtils.read(
                                compressedType = TarCompressorUtils.Type.XZ,
                                source, "*.json");
                        }
                        if (manifestData != null) {
                            JSONObject manifestJSONObject =
                                new JSONObject(new String(manifestData));
                            String contentType = manifestJSONObject
                                .optString("type", "")
                                .toUpperCase(Locale.ENGLISH);
                            String identifier = StringUtils.parseIdentifier(
                                manifestJSONObject.optString("versionName", ""));
                            JSONArray filesJSONArray =
                                manifestJSONObject.optJSONArray("files");

                            if (contentType.equals(type.name())
                                    && !identifier.isEmpty()
                                    && filesJSONArray != null) {
                                installFromPackagedFile(activity, compressedType,
                                    type, source, identifier, filesJSONArray);
                                String generatedFilename =
                                    type.lowerName() + "-" + identifier + ".tzst";
                                registerDisplayName(type, generatedFilename, identifier);
                                loadSpinner(type, spinner, identifier, defaultItem);
                            }
                        }
                        break;
                    }
                }
            } catch (JSONException e) {
                AppUtils.showToast(activity, R.string.a_network_error_occurred);
            }
        });

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    // ─────────────────────────────────────────────────────────────
    private static void showDownloadableListDialog(
            Type type, final Spinner spinner, final String defaultItem) {

        final Activity activity = (Activity) spinner.getContext();
        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);

        HttpUtils.download(
            String.format(INSTALLABLE_COMPONENTS_URL,
                type.lowerName() + "/index.txt"),
            (content) -> activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (content != null) {
                    if (content.isEmpty()) {
                        AppUtils.showToast(activity,
                            R.string.there_are_no_items_to_download);
                        return;
                    }
                    final String[] filenames = content.split("\n");
                    final String[] items = filenames.clone();
                    for (int i = 0; i < items.length; i++) {
                        items[i] = parseDisplayText(type, filenames[i]);
                    }

                    ContentDialog.showSelectionList(activity,
                        R.string.install_component, items, false,
                        (positions) -> {
                            if (!positions.isEmpty()) {
                                downloadComponentFile(type,
                                    filenames[positions.get(0)],
                                    spinner, defaultItem);
                            }
                        });
                } else {
                    AppUtils.showToast(activity,
                        R.string.a_network_error_occurred);
                }
            }));
    }

    // ─────────────────────────────────────────────────────────────
    public static void initViews(
            final Type type, View toolbox,
            final Spinner spinner,
            final String selectedItem, final String defaultItem) {

        final Context context = spinner.getContext();

        toolbox.findViewWithTag("install").setOnClickListener((v) -> {
            InstallMode installMode = type.getInstallMode();
            switch (installMode) {
                case DOWNLOAD:
                    showDownloadableListDialog(type, spinner, defaultItem);
                    break;
                case FILE:
                    openFileForInstall(
                        (MainActivity) context, type, spinner, defaultItem);
                    break;
                case BOTH:
                    PopupMenu popupMenu = new PopupMenu(context, v);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        popupMenu.setForceShowIcon(true);
                    popupMenu.inflate(R.menu.open_file_popup_menu);
                    popupMenu.setOnMenuItemClickListener((menuItem) -> {
                        int itemId = menuItem.getItemId();
                        if (itemId == R.id.menu_item_open_file) {
                            openFileForInstall(
                                (MainActivity) context, type,
                                spinner, defaultItem);
                        } else if (itemId == R.id.menu_item_download_file) {
                            showDownloadableListDialog(
                                type, spinner, defaultItem);
                        }
                        return true;
                    });
                    popupMenu.show();
                    break;
            }
        });

        toolbox.findViewWithTag("remove").setOnClickListener((v) -> {
            String identifier = spinner.getSelectedItem().toString();

            if (isBuiltinComponent(type, identifier)) {
                AppUtils.showToast(context,
                    R.string.you_cannot_remove_this_component_version);
                return;
            }

            File source = type.getSource(context, identifier);
            if (source.exists()) {
                ContentDialog.confirm(context,
                    R.string.do_you_want_to_remove_this_component_version,
                    () -> {
                        FileUtils.delete(source);
                        displayToFileMap.remove(mapKey(type, identifier));
                        loadSpinner(type, spinner, defaultItem, defaultItem);
                    });
            }
        });

        loadSpinner(type, spinner, selectedItem, defaultItem);
    }

    // ─────────────────────────────────────────────────────────────
    private static void loadSpinner(
            Type type, Spinner spinner,
            String selectedItem, String defaultItem) {

        ArrayList<String> items = getBuiltinComponentNames(type);
        items.addAll(getInstalledComponentNames(type, spinner.getContext()));

        if (type.isVersioned()) {
            items.sort((o1, o2) -> {
                int v1 = GPUHelper.vkMakeVersion(o1);
                int v2 = GPUHelper.vkMakeVersion(o2);
                if (v1 == 0 && v2 == 0) return o1.compareTo(o2);
                if (v1 == 0) return 1;
                if (v2 == 0) return -1;
                return Integer.compare(v1, v2);
            });
        }

        spinner.setAdapter(new ArrayAdapter<>(
            spinner.getContext(),
            android.R.layout.simple_spinner_dropdown_item,
            items));

        if (selectedItem == null
                || selectedItem.isEmpty()
                || !AppUtils.setSpinnerSelectionFromValue(spinner, selectedItem)) {
            AppUtils.setSpinnerSelectionFromValue(spinner, defaultItem);
        }
    }
}