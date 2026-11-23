package uz.autopvp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto PvP: to'liq moslashuvchan avtomatik jang. Hotbar/inventarda nima bo'lsa shuni ishlatadi - elytra bor-u
 * firework yo'q bo'lsa wind charge bilan uchadi, faqat qilich bo'lsa faqat qilich bilan uradi, mace/nayza/pearl
 * bo'lsa ulardan ham foydalanadi. Kerak bo'lsa o'zi ovqat yeydi va totem tanlaydi.
 */
public class PvpConfig {
    /** Tugma (standart K) bilan yoqiladi, o'chirguningizcha o'zi jang qiladi. */
    public boolean enabled = false;
    /** Nishon qidirish masofasi (blok). */
    public int range = 48;

    // ------------------------------------------------------------ yaqin jang
    /** Yaqinda urish. */
    public boolean melee = true;
    /** Urish masofasi (0.1 blok). */
    public int reachTenths = 32;
    /** Yaqinda ham nayza bilan (jab) uradi; OFF bo'lsa qilich (bo'lmasa bolta, bo'lmasa mace). */
    public boolean spearMelee = true;
    /** Nishon qalqon ushlasa bolta bilan o'chiradi (stun). */
    public boolean axeShield = true;
    /** Tushayotganda mace (zirhli nishonga Breach, aks holda Density). */
    public boolean mace = true;
    /** Mace uchun kamida shuncha tushish (blok). */
    public int maceMinFall = 2;
    /** Ochlik shundan kam bo'lsa Lunge/jab qilmaydi (piercing qurol ochlik yeydi). */
    public int minFood = 6;

    // ------------------------------------------------------------ uzoqdan yaqinlashish
    /** Uzoqda nayza bilan Lunge qilib uchib boradi (boshqa variantlarning hech biri mos kelmasa). */
    public boolean lunge = true;
    /** Uzoqda pitch = 0 (Lunge impulsi to'liq gorizontal). */
    public boolean flatten = true;
    /** Juda uzoqda (va boshqa variant yo'q/mos kelmasa) nishon oldiga ender pearl otadi. */
    public boolean pearl = true;
    public int pearlDist = 18;
    /** Yaqin-o'rta masofada: oyoq ostiga wind charge - sakrab, tushishda mace bilan uradi. */
    public boolean windJump = true;
    public int windJumpDist = 5;
    /** O'rtacha masofada: pearl + wind charge (Pearl Boost) bilan tepaga teleport, keyin mace. */
    public boolean pearlUp = true;
    public int pearlUpDist = 14;
    /** Qalqon ushlasa stun slam: bolta + mace bir tickda. */
    public boolean stunSlam = true;
    /** Siz boshqa tomonga yurmasangiz nishonga o'zi yaqinlashadi. */
    public boolean walk = true;

    // ------------------------------------------------------------ elytra
    /** Elytra bor va foydali bo'lsa: uchib borib, ustidan mace bilan tushadi. */
    public boolean elytra = true;
    /** Nishon shundan uzoq bo'lsa uchadi (blok). */
    public int flyDist = 12;
    /** Nishondan shuncha baland uchib boradi, keyin sho'ng'iydi. */
    public int attackHeight = 18;
    /** Yetib borishga shuncha tick qolganda elytra yechiladi (tez uchsa avtomatik oldinroq). */
    public int unequipLead = 3;
    /** Elytra shundan kam durability bo'lsa ishlatilmaydi. */
    public int minDurability = 10;
    /** Firework'lar orasidagi eng kam kechikish (tick). */
    public int fireworkDelay = 20;
    /** Firework bo'lmasa: oyoq ostiga wind charge - tepaga otilib, elytra bilan sirpanib boradi. */
    public boolean windTakeoff = true;
    /** Yerda elytra kiyib qolgan bo'lsa nagrudnikni kiyadi. */
    public boolean armorOnGround = true;

    // ------------------------------------------------------------ omon qolish
    /** Jon shundan kam/teng bo'lsa ovqat (avval oltin olma) yeydi. */
    public int eatHealth = 8;
    /** Ovqatdan oldin nishondan uzoqqa pearl otib qochadi. */
    public boolean retreatPearl = true;
    public int retreatDist = 14;
    /** Jon + absorption shundan kam/teng bo'lsa totemni o'zi chap qo'lga oladi (0 = o'chiq). */
    public int totemHealth = 10;
    public boolean totem = true;

    // ------------------------------------------------------------ nishon
    public boolean players = true;
    public boolean mobs = false;
    public boolean ignoreFriends = true;
    public boolean messages = true;
    public boolean hud = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PvpConfig instance = new PvpConfig();

    public static PvpConfig get() { return instance; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("autopvp.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                PvpConfig c = GSON.fromJson(Files.readString(p), PvpConfig.class);
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
