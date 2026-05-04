package minetweaker.util;

import minetweaker.MineTweakerAPI;
import minetweaker.api.mods.IMod;

public final class ModOnlyHelper {

    private ModOnlyHelper() {}

    /**
     * Used by generated registries to avoid resolving classes for optional mods before the mod presence check.
     */
    public static boolean isModOnlyLoaded(String[] mods, String version) {
        if (mods == null) return false;
        for (String mod : mods) {
            if (!isModOnlyLoaded(mod, version)) return false;
        }

        return true;
    }

    public static boolean isModOnlyLoaded(String mod, String version) {
        if (mod == null) return false;
        if (MineTweakerAPI.loadedMods == null) return false;
        if (version == null) version = "";

        if (!MineTweakerAPI.loadedMods.contains(mod)) return false;

        if (!version.isEmpty()) {
            IMod loadedMod = MineTweakerAPI.loadedMods.get(mod);
            return loadedMod != null && loadedMod.getVersion().startsWith(version);
        }

        return true;
    }
}
