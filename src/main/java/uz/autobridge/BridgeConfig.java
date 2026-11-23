package uz.autobridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class BridgeConfig {
    /** Yoqilsa: modul o'zi yuradi (W/shift kerak emas), qo'ygan yo'nalishida devorga yetguncha boradi. */
    public boolean enabled = false;
    /** BridgeStyle tartib raqami - qaysi uslub bilan ko'prik qurilishi (ClickGUI'da "Turi"). */
    public int style = BridgeStyle.CLASSIC.ordinal();
    /** Hotbar'da qurilish bloki bo'lmasa inventardan oladi (keyin joyiga qaytaradi). */
    public boolean fromInventory = true;
    /** Yo'nalish diagonalga (NE/NW/SE/SW) yaqin bo'lsa ikki tomon orasida almashlab (zigzag) quradi. */
    public boolean diagonal = false;
    /** Oldinda suv/lava bo'lsa ustidan o'tib ko'prik qurmay, to'xtaydi. */
    public boolean stopAtLiquid = false;
    /** Shuncha blok qo'yilgach o'zi to'xtaydi (0 = chegarasiz). */
    public int maxBlocks = 0;
    /** Yaqinda begona o'yinchi ko'rinsa pauza qiladi (qurmaydi, yurmaydi), ketsa davom etadi. */
    public boolean pauseOnEnemy = false;
    /** "Dushman bor" deb hisoblanadigan masofa (blok). */
    public int enemyRange = 10;
    public boolean messages = true;
    public boolean hud = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BridgeConfig instance = new BridgeConfig();

    public static BridgeConfig get() { return instance; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("autobridge.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                BridgeConfig c = GSON.fromJson(Files.readString(p), BridgeConfig.class);
                if (c != null) instance = c;
            }
            save();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
