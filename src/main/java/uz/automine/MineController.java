package uz.automine;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import uz.spearlunger.InvHelper;
import uz.spearlunger.Rotations;

import java.util.List;

/**
 * Auto Mine: belgilangan hudud (markaz + radius) ichida ro'yxatdagi rudalarni qazib ketadi, hech qachon hududdan
 * tashqariga chiqmaydi. To'g'ridan-to'g'ri nishon ruda tomon "tunnel" qaziydi (A* emas - sodda, eng katta masofali
 * o'qdan boshlab bir qadam, kerak bo'lsa oldidagi blokni buzib). Inventar to'lganda sotish buyrug'ini (masalan
 * /buyer) yuboradi, keraksiz bloklarni yerga tashlaydi. Harakat haqiqiy tugma bosish orqali (odam qo'lidek).
 */
public class MineController {
    private BlockPos target = null;
    private int oreIndex = 0;
    private int sellCooldown = 0, rescanCooldown = 0;
    private String status = null, lastWarn = null;

    public void tick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        AutoMineConfig cfg = AutoMineConfig.get();
        while (AutoMineClient.keyToggle.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            AutoMineConfig.save();
            if (p != null) p.sendMessage(Text.literal(cfg.enabled ? "§aAuto Mine: ON" : "§cAuto Mine: OFF"), true);
            if (!cfg.enabled) { release(c); target = null; }
        }
        if (p == null || c.world == null || c.interactionManager == null) {
            release(c);
            return;
        }
        if (!cfg.enabled) return;
        if (!cfg.areaSet() || cfg.ores.isEmpty()) {
            release(c);
            status = "hudud (/mine area) yoki ruda ro'yxati belgilanmagan";
            return;
        }
        if (InvHelper.screenOpen(c) || p.isSpectator() || !p.isAlive()) {
            release(c);
            return;
        }
        release(c);
        if (sellCooldown > 0) sellCooldown--;
        if (rescanCooldown > 0) rescanCooldown--;
        ClientWorld w = c.world;

        // ---- 1. inventar to'lgan - sotish
        if (sellCooldown == 0 && fullSlots(p) >= cfg.sellFullSlots) {
            p.networkHandler.sendChatCommand(cfg.sellCommand);
            sellCooldown = 100;
            status = "sotilmoqda (/" + cfg.sellCommand + ")";
            if (cfg.messages) p.sendMessage(Text.literal("§bAuto Mine: /" + cfg.sellCommand), true);
            return;
        }

        // ---- 2. keraksiz bloklarni tashlash
        if (cfg.dropJunk) {
            int junk = findJunkSlot(p, cfg);
            if (junk >= 0) {
                dropSlot(c, p, junk);
                status = "keraksiz tashlanmoqda";
                return;
            }
        }

        // ---- 3. nishon rudani tekshirish/qidirish
        if (target != null && (!inArea(target, cfg) || !isOre(w.getBlockState(target), cfg))) target = null;
        if (target == null) {
            if (rescanCooldown == 0) {
                target = findTarget(w, p, cfg);
                rescanCooldown = 100;
            }
            if (target == null) {
                status = cfg.onDepleted == 0 ? "ruda yo'q - kutilmoqda" : "ruda yo'q";
                return;
            }
        }

        // ---- 4. yetib keldikmi - qazish, aks holda tunnel
        if (p.getEyePos().distanceTo(Vec3d.ofCenter(target)) <= p.getBlockInteractionRange()) {
            mineBlock(c, p, target, cfg, true);
        } else {
            tunnelToward(c, p, cfg);
        }
    }

    // ------------------------------------------------------------ nishon qidirish
    /** Ro'yxatdagi joriy ruda turidan (topilmasa - "almashtirish" rejimida - keyingi turlardan) eng yaqinini topadi. */
    private BlockPos findTarget(ClientWorld w, ClientPlayerEntity p, AutoMineConfig cfg) {
        BlockPos center = new BlockPos(cfg.centerX, cfg.centerY, cfg.centerZ);
        int r = Math.max(1, cfg.radius);
        int tries = cfg.ores.size();
        while (tries-- > 0) {
            String id = cfg.ores.get(Math.floorMod(oreIndex, cfg.ores.size()));
            Identifier rid = Identifier.tryParse(id);
            Block block = rid == null ? null : Registries.BLOCK.getOrEmpty(rid).orElse(null);
            if (block != null) {
                BlockPos best = null;
                double bestD = Double.MAX_VALUE;
                for (BlockPos pos : BlockPos.iterate(center.add(-r, -r, -r), center.add(r, r, r))) {
                    if (!w.isChunkLoaded(pos)) continue;
                    if (!w.getBlockState(pos).isOf(block)) continue;
                    double d = p.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (d < bestD) {
                        bestD = d;
                        best = pos.toImmutable();
                    }
                }
                if (best != null) return best;
            }
            if (cfg.onDepleted != 1) break;   // "kutish" rejimi - boshqa turga o'tmaymiz
            oreIndex = Math.floorMod(oreIndex + 1, cfg.ores.size());
        }
        return null;
    }

    private boolean isOre(BlockState st, AutoMineConfig cfg) {
        String id = Registries.BLOCK.getId(st.getBlock()).toString();
        return cfg.ores.contains(id);
    }

    private static boolean inArea(BlockPos pos, AutoMineConfig cfg) {
        int r = cfg.radius;
        return Math.abs(pos.getX() - cfg.centerX) <= r && Math.abs(pos.getY() - cfg.centerY) <= r
                && Math.abs(pos.getZ() - cfg.centerZ) <= r;
    }

    // ------------------------------------------------------------ tunnel qazish
    /**
     * Nishongacha qolgan dx/dy/dz'dan eng kattasi bo'yicha bitta qadam: kerak bo'lsa yo'ldagi blok(lar)ni buzib,
     * aks holda haqiqiy tugma bosib oldinga/tepaga/pastga siljiydi. Hech qachon hudud tashqarisiga chiqmaydi - agar
     * keyingi qadam tashqarida bo'lsa, shu nishon tashlab, keyingi tickda yangisi qidiriladi.
     */
    private void tunnelToward(MinecraftClient c, ClientPlayerEntity p, AutoMineConfig cfg) {
        ClientWorld w = c.world;
        BlockPos feet = p.getBlockPos();
        BlockPos goal = target;
        int dx = goal.getX() - feet.getX(), dy = goal.getY() - feet.getY(), dz = goal.getZ() - feet.getZ();
        int adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        int sx = Integer.signum(dx), sy = Integer.signum(dy), sz = Integer.signum(dz);

        BlockPos next;
        boolean goingUp = false, goingDown = false;
        if (ady >= adx && ady >= adz && sy != 0) {
            next = feet.up(sy);
            goingUp = sy > 0;
            goingDown = sy < 0;
        } else if (adx >= adz) {
            next = feet.add(sx, 0, 0);
        } else {
            next = feet.add(0, 0, sz);
        }

        if (!inArea(next, cfg)) {
            target = null;
            status = "hudud chegarasi - boshqa ruda";
            return;
        }

        BlockPos firstBreak;
        if (goingUp) firstBreak = feet.up(2);
        else if (goingDown) firstBreak = feet.down();
        else firstBreak = next;
        if (!w.getBlockState(firstBreak).getCollisionShape(w, firstBreak).isEmpty()) {
            mineBlock(c, p, firstBreak, cfg, false);
            status = "tunnel qazilmoqda";
            return;
        }
        if (!goingUp && !goingDown) {
            BlockPos head = next.up();
            if (!w.getBlockState(head).getCollisionShape(w, head).isEmpty()) {
                mineBlock(c, p, head, cfg, false);
                status = "tunnel qazilmoqda";
                return;
            }
        }

        Vec3d center = Vec3d.ofBottomCenter(next);
        Rotations.turn(p, Rotations.yawTo(p.getEntityPos(), center), 12.0f, 3.0f);
        GameOptions o = c.options;
        o.forwardKey.setPressed(true);
        if (goingUp) o.jumpKey.setPressed(true);
        if (goingDown) o.sneakKey.setPressed(true);
        status = "qazib borish";
    }

    /** Eng mos asbobni tanlab buzadi. isOreBlock=true bo'lsa (nishon rudaning o'zi) ketmon sinishi sozlamasi ishlaydi. */
    private void mineBlock(MinecraftClient c, ClientPlayerEntity p, BlockPos b, AutoMineConfig cfg, boolean isOreBlock) {
        BlockState st = c.world.getBlockState(b);
        if (st.isAir() || st.getCollisionShape(c.world, b).isEmpty()) {
            if (isOreBlock) target = null;
            return;
        }
        int tool = bestTool(p, st, -1);
        if (tool >= 0) {
            ItemStack ts = p.getInventory().getStack(tool);
            if (ts.isDamageable() && ts.willBreakNextUse()) {
                if (cfg.toolBreakMode == 0) {
                    status = "ketmon sinishiga oz qoldi - to'xtadi";
                    warnOnce(p, cfg, "§cAuto Mine: ketmon sinishiga oz qoldi - to'xtadi, o'zingiz almashtiring.");
                    return;
                }
                int alt = bestTool(p, st, tool);
                if (alt >= 0) tool = alt;
            }
        }
        if (tool >= 0) InvHelper.select(p, tool);
        Vec3d eye = p.getEyePos(), center = Vec3d.ofCenter(b);
        if (Rotations.turn(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center), 6.0f)) {
            c.options.attackKey.setPressed(true);
        }
    }

    /** Hotbar'da shu blok uchun eng tez asbob (exclude - shu slotni hisobga olmaydi, sinayotgan asbobni chetlab). */
    private static int bestTool(ClientPlayerEntity p, BlockState st, int exclude) {
        int tool = -1;
        float best = 1.0f;
        for (int i = 0; i < 9; i++) {
            if (i == exclude) continue;
            float sp = p.getInventory().getStack(i).getMiningSpeedMultiplier(st);
            if (sp > best) {
                best = sp;
                tool = i;
            }
        }
        return tool;
    }

    // ------------------------------------------------------------ inventar
    private static int fullSlots(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (!p.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    /** Ro'yxatdagi (keraksiz), lekin nishon-ruda ro'yxatida bo'lmagan birinchi slot. */
    private static int findJunkSlot(ClientPlayerEntity p, AutoMineConfig cfg) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).toString();
            if (cfg.junk.contains(id) && !cfg.ores.contains(id)) return i;
        }
        return -1;
    }

    /** Slotni (kerak bo'lsa hotbar'ga olib kelib) tanlaydi va butun stekni yerga tashlaydi. */
    private static void dropSlot(MinecraftClient c, ClientPlayerEntity p, int slot) {
        int original = p.getInventory().getSelectedSlot();
        int hb = slot;
        if (slot >= 9) {
            hb = original < 9 ? original : 8;
            InvHelper.swap(c, p, slot, hb);
        }
        InvHelper.select(p, hb);
        p.dropSelectedItem(true);
        if (slot >= 9) InvHelper.swap(c, p, slot, hb);
        InvHelper.select(p, original);
    }

    // ------------------------------------------------------------ buyruqlar
    public void setArea(int radius) {
        MinecraftClient c = MinecraftClient.getInstance();
        ClientPlayerEntity p = c.player;
        if (p == null) return;
        AutoMineConfig cfg = AutoMineConfig.get();
        BlockPos pos = p.getBlockPos();
        cfg.centerX = pos.getX();
        cfg.centerY = pos.getY();
        cfg.centerZ = pos.getZ();
        cfg.radius = radius;
        AutoMineConfig.save();
        target = null;
        p.sendMessage(Text.literal(String.format("§aAuto Mine: hudud o'rnatildi - markaz (%d,%d,%d), radius %d",
                pos.getX(), pos.getY(), pos.getZ(), radius)), true);
    }

    public void start(List<String> ores) {
        MinecraftClient c = MinecraftClient.getInstance();
        ClientPlayerEntity p = c.player;
        AutoMineConfig cfg = AutoMineConfig.get();
        cfg.ores.clear();
        for (String o : ores) cfg.ores.add(o.contains(":") ? o : "minecraft:" + o);
        cfg.enabled = true;
        AutoMineConfig.save();
        target = null;
        oreIndex = 0;
        if (p != null) {
            if (!cfg.areaSet()) {
                p.sendMessage(Text.literal("§eAuto Mine: hudud belgilanmagan - avval /mine area <radius>"), true);
            } else {
                p.sendMessage(Text.literal("§aAuto Mine: boshladik - " + String.join(", ", cfg.ores)), true);
            }
        }
    }

    public void stop(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        AutoMineConfig.get().enabled = false;
        AutoMineConfig.save();
        release(c);
        target = null;
        if (msg != null && c.player != null) c.player.sendMessage(Text.literal(msg), true);
    }

    // ------------------------------------------------------------ yordamchi
    private static void release(MinecraftClient c) {
        GameOptions o = c.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sneakKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.attackKey.setPressed(false);
    }

    private void warnOnce(ClientPlayerEntity p, AutoMineConfig cfg, String msg) {
        if (cfg.messages && !msg.equals(lastWarn)) {
            p.sendMessage(Text.literal(msg), true);
            lastWarn = msg;
        }
    }

    public String hudLine() {
        AutoMineConfig cfg = AutoMineConfig.get();
        if (!cfg.enabled || !cfg.hud || status == null) return null;
        return "§6Auto Mine: §f" + status;
    }
}
