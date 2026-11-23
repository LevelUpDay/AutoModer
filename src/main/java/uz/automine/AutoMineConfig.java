package uz.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto Mine: belgilangan hudud (markaz + radius) ichida tanlangan rudalarni qazib, hech qachon hududdan
 * tashqariga chiqmaydi. Inventar to'lganda sotish buyrug'ini (masalan /buyer) yuboradi, keraksiz bloklarni
 * yerga tashlaydi.
 */
public class AutoMineConfig {
    public boolean enabled = false;

    /** Markaz: "/mine area <radius>" yoki ClickGUI "Markazni shu yerga qo'y" bilan o'rnatiladi. */
    public int centerX = 0, centerY = Integer.MIN_VALUE, centerZ = 0;
    public int radius = 16;

    /** Qazib olinadigan bloklar (to'liq id, masalan "minecraft:diamond_ore"). */
    public List<String> ores = new ArrayList<>(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));
    /** Joriy ruda hudud ichida tugasa: 0 = kutadi (davriy qayta tekshiradi), 1 = ro'yxatdagi keyingisiga o'tadi. */
    public int onDepleted = 1;
    /** Ketmon sinishiga oz qolsa: 0 = to'xtaydi (xabar beradi), 1 = avtomatik boshqa ketmonga almashadi. */
    public int toolBreakMode = 1;

    /** Inventar (36 slot) shuncha slot TO'LGANDA sotish buyrug'i yuboriladi. */
    public int sellFullSlots = 33;
    /** "/" belgisiz - masalan "buyer" {@literal ->} /buyer sifatida yuboriladi. */
    public String sellCommand = "buyer";

    /** Jon shundan kam/teng bo'lsa - boshqa hamma narsadan ustun - Experience Bottle ishlatib davolanadi
     *  (serverning o'z mexanikasi; vanilla'da bu item otiladi, lekin ko'p anarxiya serverlarida ichib davolaydi). */
    public boolean autoHeal = true;
    public int healHealth = 10;

    public boolean dropJunk = true;
    /** Qazib olingach kerak bo'lmasa yerga tashlanadigan bloklar (ruda ro'yxatidagilar hech qachon tashlanmaydi). */
    public List<String> junk = new ArrayList<>(List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:stone", "minecraft:deepslate", "minecraft:gravel", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite", "minecraft:tuff", "minecraft:dirt"));

    public boolean messages = true;
    public boolean hud = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AutoMineConfig instance = new AutoMineConfig();

    public static AutoMineConfig get() { return instance; }

    public boolean areaSet() { return centerY != Integer.MIN_VALUE; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                AutoMineConfig c = GSON.fromJson(Files.readString(p), AutoMineConfig.class);
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
