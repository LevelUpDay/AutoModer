package uz.autopvp;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import uz.autotnt.AutoTntConfig;
import uz.autotravel.TravelController;
import uz.pearlboost.PearlBoostClient;
import uz.spearlunger.ElytraHelper;
import uz.spearlunger.InvHelper;
import uz.spearlunger.PearlHelper;
import uz.spearlunger.Rotations;

/**
 * Auto PvP: tugma bilan yoqiladi, o'chirguningizcha eng yaqin dushman bilan o'zi to'liq jang qiladi - hotbar va
 * inventarda nima bo'lsa shuni ishlatib: qilich/bolta/mace/nayza bilan yaqinda, pearl/wind charge/nayza bilan
 * yaqinlashib, elytra bor va foydali bo'lsa (firework yoki - bo'lmasa - wind charge bilan) uchib borib tepadan
 * tushadi. Kerak bo'lsa o'zi ovqat yeydi (qochib) va totemni chap qo'lga oladi. Siz faqat yurasiz/qarab turasiz -
 * modul sizning harakat tugmalaringizga tegmaydi (siz bosmasangiz o'zi yuradi).
 */
public class PvpController {
    private enum Phase { GROUND, TAKEOFF, CRUISE, FALL_HIT, PEARL_UP, EAT }

    private Phase phase = Phase.GROUND;
    private LivingEntity target = null;
    private Vec3d lastTargetPos = null, tvel = Vec3d.ZERO, pearlFrom = null;
    private int phaseTicks = 0, fwCd = 0, hitCd = 0, flyBan = 0, pearlCooldown = 0, eatCount = -1;
    private boolean windTakeoff = false, hitThisFall = false;
    private String status = "", last = null;

    public void tick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null || c.interactionManager == null) {
            phase = Phase.GROUND;
            target = null;
            return;
        }
        PvpConfig cfg = PvpConfig.get();
        while (AutoPvpClient.keyToggle.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            PvpConfig.save();
            p.sendMessage(Text.literal(cfg.enabled ? "§aAuto PvP: ON" : "§cAuto PvP: OFF"), true);
            if (!cfg.enabled) reset(c);
        }
        if (!cfg.enabled) return;
        boolean user = userMoving(c);
        releaseOurs(c);
        if (InvHelper.screenOpen(c) || p.isSpectator() || !p.isAlive()) return;
        if (fwCd > 0) fwCd--;
        if (hitCd > 0) hitCd--;
        if (flyBan > 0) flyBan--;
        if (pearlCooldown > 0) pearlCooldown--;

        maybePopTotem(c, p, cfg);   // har qanday fazada, eng birinchi, har tick

        target = pick(c, p, cfg);
        if (target == null) {
            status = "nishon yo'q";
            lastTargetPos = null;
            if (phase != Phase.EAT && phase != Phase.CRUISE) phase = Phase.GROUND;
            if (phase == Phase.CRUISE && !p.isGliding()) phase = Phase.GROUND;
            if (phase == Phase.EAT) eat(c, p, cfg);
            return;
        }
        Vec3d tp = target.getEntityPos();
        if (lastTargetPos != null) tvel = tvel.multiply(0.5).add(tp.subtract(lastTargetPos).multiply(0.5));
        lastTargetPos = tp;

        // jon kam - qochib ovqat (uchish/tushish/teleport o'rtasida bo'lmasa)
        if (phase != Phase.EAT && phase != Phase.CRUISE && phase != Phase.FALL_HIT && phase != Phase.PEARL_UP
                && p.getHealth() <= cfg.eatHealth && foodSlot(p) >= 0) {
            startEat(c, p, cfg);
        }
        phaseTicks++;
        switch (phase) {
            case GROUND -> ground(c, p, cfg, user);
            case TAKEOFF -> takeoff(c, p, cfg);
            case CRUISE -> cruise(c, p, cfg);
            case FALL_HIT -> fallHit(c, p, cfg, user);
            case PEARL_UP -> pearlUp(p);
            case EAT -> eat(c, p, cfg);
        }
    }

    private void set(Phase ph) {
        phase = ph;
        phaseTicks = 0;
    }

    private void reset(MinecraftClient c) {
        releaseOurs(c);
        c.options.useKey.setPressed(false);
        phase = Phase.GROUND;
        target = null;
    }

    // ------------------------------------------------------------ totem (har doim, instant)
    /** Jon+absorption kam qolsa, boshqa oyna ochiq bo'lmasa, totemni chap qo'lga darhol almashtiradi. */
    private static void maybePopTotem(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        if (!cfg.totem || cfg.totemHealth <= 0) return;
        if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (p.getHealth() + p.getAbsorptionAmount() > cfg.totemHealth) return;
        if (c.currentScreen != null) return;
        int slot = findTotemSlot(p);
        if (slot < 0) return;
        c.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
    }

    /** PlayerScreenHandler slot raqami: avval asosiy inventar (9-35), keyin hotbar (36-44). */
    private static int findTotemSlot(ClientPlayerEntity p) {
        for (int i = 9; i < 36; i++) if (p.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return 36 + i;
        return -1;
    }

    // ------------------------------------------------------------ yerda
    private void ground(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg, boolean user) {
        LivingEntity t = target;
        Vec3d eye = p.getEyePos(), center = t.getBoundingBox().getCenter();
        double reachDist = eye.distanceTo(closest(t, eye));
        double d = horizontal(p.getEntityPos(), t.getEntityPos());
        float yaw = Rotations.yawTo(p.getEntityPos(), t.getEntityPos());
        boolean grounded = p.isOnGround();
        double reach = cfg.reachTenths / 10.0;
        status = String.format("jang %.0fm", d);

        // ---- tushayotganda mace (tabiiy sakrash/yiqilishda ham) ----
        int mace = cfg.mace ? maceSlot(p, t) : -1;
        if (mace >= 0 && !grounded && p.fallDistance >= cfg.maceMinFall && p.getVelocity().y < 0
                && reachDist <= reach + 0.3) {
            if (Rotations.turn(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center))) {
                InvHelper.select(p, mace);
                c.interactionManager.attackEntity(p, t);
                p.swingHand(Hand.MAIN_HAND);
                status = "mace";
            }
            return;
        }

        // ---- reach ichida: barqaror yaqin jang ----
        if (cfg.melee && reachDist <= reach) {
            meleeFight(c, p, t, cfg, false, user);
            return;
        }

        // ---- reach'dan tashqarida: qanday yaqinlashish/hujum qilish ----
        if (cfg.elytra && grounded && flyBan == 0 && d > cfg.flyDist && canFly(p, cfg)) {
            windTakeoff = false;
            set(Phase.TAKEOFF);
            return;
        }
        if (grounded && cfg.armorOnGround && ElytraHelper.wearingElytra(p) && ElytraHelper.chestplateSlot(p) >= 0) {
            ElytraHelper.unequipElytra(c, p);
            return;
        }
        if (cfg.pearlUp && grounded && d >= 5 && d <= cfg.pearlUpDist && ready(p, Items.ENDER_PEARL)
                && ready(p, Items.WIND_CHARGE) && mace >= 0 && !PearlBoostClient.CONTROLLER.busy()) {
            if (PearlBoostClient.CONTROLLER.launch(c, p, yaw, -65.0f)) {
                pearlFrom = p.getEntityPos();
                say(p, "§dAuto PvP: pearl bilan tepaga");
                set(Phase.PEARL_UP);
                return;
            }
        }
        int wind = hotbar(p, Items.WIND_CHARGE);
        if (cfg.windJump && grounded && d <= cfg.windJumpDist && wind >= 0 && mace >= 0) {
            windJump(c, p, wind, yaw);
            hitThisFall = false;
            set(Phase.FALL_HIT);
            return;
        }
        if (cfg.pearl && grounded && pearlCooldown == 0 && reachDist >= cfg.pearlDist
                && PearlHelper.throwAt(c, p, t.getEntityPos().add(0, 0.05, 0))) {
            pearlCooldown = 40;
            status = "pearl";
            return;
        }
        int spear = cfg.lunge ? spearSlot(p) : -1;
        if (spear >= 0) {
            float pitch = cfg.flatten && reachDist > 6 ? 0.0f : Rotations.pitchTo(eye, center);
            boolean aimed = Rotations.turn(p, Rotations.yawTo(eye, center), pitch);
            if (aimed) InvHelper.select(p, spear);
            if (aimed && lungeReady(c, p, cfg)) {
                c.interactionManager.attackWithPiercingWeapon(p.getMainHandStack().get(DataComponentTypes.PIERCING_WEAPON));
                p.swingHand(Hand.MAIN_HAND);
                status = "lunge";
            } else {
                status = "lunge kutish";
            }
            return;
        }
        if (!user) {
            Rotations.turn(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center), 4.0f);
            if (cfg.walk && p.distanceTo(t) > 2.6) {
                press(c.options.forwardKey);
                if (d > 4 && p.getHungerManager().getFoodLevel() > 6) press(c.options.sprintKey);
                if (grounded && p.horizontalCollision) p.jump();
            }
        }
        status = "yaqinlashish";
    }

    private static boolean canFly(ClientPlayerEntity p, PvpConfig cfg) {
        if (ElytraHelper.elytraSlot(p, cfg.minDurability) == -2) return false;
        return ElytraHelper.fireworks(p) > 0 || (cfg.windTakeoff && hotbar(p, Items.WIND_CHARGE) >= 0);
    }

    // ------------------------------------------------------------ uchish
    private void takeoff(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        status = "ko'tarilish";
        if (phaseTicks > 40 || p.isTouchingWater()) {
            flyBan = 100;
            set(Phase.GROUND);
            return;
        }
        if (!ElytraHelper.wearingElytra(p)) {
            int s = ElytraHelper.elytraSlot(p, cfg.minDurability);
            if (s < 0) {
                set(Phase.GROUND);
                return;
            }
            ElytraHelper.equip(c, p, s);
            return;
        }
        Vec3d tp = target.getEntityPos();
        float yaw = Rotations.yawTo(p.getEntityPos(), tp);
        if (p.isGliding()) {
            if (ElytraHelper.fireworks(p) > 0 && ElytraHelper.firework(c, p)) fwCd = cfg.fireworkDelay;
            set(Phase.CRUISE);
            return;
        }
        if (p.isOnGround()) {
            int wind = hotbar(p, Items.WIND_CHARGE);
            if (ElytraHelper.fireworks(p) == 0 && wind >= 0) {
                windJump(c, p, wind, yaw);   // firework yo'q - wind charge bilan tepaga
                windTakeoff = true;
            } else {
                Rotations.turn(p, yaw, -20.0f, 3.0f);
                p.jump();
            }
            return;
        }
        if (windTakeoff ? p.getVelocity().y < 0.15 : phaseTicks >= 3) ElytraHelper.deploy(p);
    }

    private void cruise(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        LivingEntity t = target;
        if (!p.isGliding()) {
            if (p.isOnGround() || p.isTouchingWater()) set(Phase.GROUND);
            else if (p.getVelocity().y < 0) ElytraHelper.deploy(p);
            return;
        }
        if (phaseTicks > 900) {
            ElytraHelper.unequipElytra(c, p);
            set(Phase.FALL_HIT);
            return;
        }
        Vec3d pos = p.getEntityPos(), v = p.getVelocity();
        double speed = Math.max(0.4, v.length()), hs = Math.sqrt(v.x * v.x + v.z * v.z);
        Vec3d tc = t.getBoundingBox().getCenter();
        double reachTicks = Math.min(30, tc.distanceTo(pos) / speed);
        Vec3d lead = tc.add(tvel.x * reachTicks, 0, tvel.z * reachTicks);
        double hd = horizontal(pos, lead);
        double alt = pos.y - t.getY();
        boolean fw = ElytraHelper.fireworks(p) > 0;
        double diveDist = Math.max(8, alt);
        float yaw, pitch;
        boolean boost = false;
        if (alt < 4 && hd < 12) {
            if (!fw) {
                ElytraHelper.unequipElytra(c, p);
                hitThisFall = false;
                set(Phase.FALL_HIT);
                return;
            }
            yaw = Rotations.yawTo(pos, lead);
            pitch = -50.0f;
            boost = true;
            status = "ko'tarilish";
        } else if (hd > diveDist) {
            Vec3d dir = new Vec3d(lead.x - pos.x, 0, lead.z - pos.z).normalize();
            double desired = Math.max(t.getY() + cfg.attackHeight,
                    TravelController.terrainAhead(c.world, pos, dir, Math.min(64, hd)) + 6);
            double dy = desired - pos.y;
            yaw = Rotations.yawTo(pos, lead);
            pitch = (float) MathHelper.clamp(-dy * 1.5, -40, 20);
            boost = hs < 1.1 || (dy > 6 && v.y < 0.3);
            if (TravelController.wallAhead(c.world, p, dir, Math.max(10, hs * 20))) {
                pitch = -55.0f;
                boost = true;
            }
            status = String.format("uchish %.0fm, +%.0f", hd, alt);
        } else {
            Vec3d eye = p.getEyePos();
            yaw = Rotations.yawTo(eye, lead);
            pitch = Math.max(15.0f, Rotations.pitchTo(eye, lead));
            Vec3d to = lead.subtract(pos.add(0, 0.9, 0));
            double eta = to.length() / speed;
            int leadT = cfg.unequipLead + (int) Math.round(Math.max(0, speed - 1.0) * 2);
            status = String.format("sho'ng'ish %.0f tick", eta);
            if (eta <= leadT && v.normalize().dotProduct(to.normalize()) >= 0.5) {
                ElytraHelper.unequipElytra(c, p);
                hitThisFall = false;
                set(Phase.FALL_HIT);
                if (inReach(p, t, cfg)) meleeFight(c, p, t, cfg, true, false);
                return;
            }
        }
        if (boost && fw && fwCd == 0 && ElytraHelper.firework(c, p)) fwCd = cfg.fireworkDelay;
        Rotations.turn(p, yaw, pitch, 2.0f);
    }

    // ------------------------------------------------------------ tushib urish
    private void fallHit(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg, boolean user) {
        LivingEntity t = target;
        status = String.format("tushish %.1f", p.fallDistance);
        if (p.isGliding()) {
            set(Phase.CRUISE);
            return;
        }
        Vec3d eye = p.getEyePos(), center = t.getBoundingBox().getCenter();
        if (!user) {
            Rotations.turn(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center), 30.0f);
            if (!p.isOnGround() && horizontal(p.getEntityPos(), t.getEntityPos()) > 0.8) press(c.options.forwardKey);
        }
        boolean falling = p.getVelocity().y < 0 && (p.fallDistance >= 1.5 || phaseTicks > 3);
        if (!hitThisFall && hitCd == 0 && falling && inReach(p, t, cfg)) {
            meleeFight(c, p, t, cfg, true, user);
            hitThisFall = true;
            hitCd = 4;
        }
        if ((p.isOnGround() || p.isTouchingWater()) && phaseTicks > 2 || phaseTicks > 120) set(Phase.GROUND);
    }

    private void pearlUp(ClientPlayerEntity p) {
        status = "pearl";
        boolean teleported = pearlFrom != null && p.getEntityPos().distanceTo(pearlFrom) > 4 && !p.isOnGround();
        if (teleported) {
            hitThisFall = false;
            set(Phase.FALL_HIT);
        } else if (!PearlBoostClient.CONTROLLER.busy() && phaseTicks > 3 || phaseTicks > 100) {
            flyBan = Math.max(flyBan, 20);
            set(Phase.GROUND);
        }
    }

    /**
     * Yaqin jang zarbasi. diving=false (GROUND, barqaror): nayza jab (tayyor bo'lsa, qalqon bo'lmasa) yoki navbat
     * bilan to'g'ri qurolni tanlab (bir tick kutib) cooldown to'lganda uradi. diving=true (FALL_HIT/sho'ng'ishdan
     * keyin, bir martalik imkoniyat): qalqon bo'lsa bolta bilan stun slam, keyin (yoki aks holda) darhol
     * nayza/mace/qilich/bolta bilan bir tickda tanlab-urib yuboradi.
     */
    private void meleeFight(MinecraftClient c, ClientPlayerEntity p, LivingEntity t, PvpConfig cfg, boolean diving,
                            boolean user) {
        Vec3d eye = p.getEyePos(), center = t.getBoundingBox().getCenter();
        boolean shieldUp = t.isBlocking();
        int axe = find(p, "_axe");
        int spear = spearSlot(p);

        if (diving) {
            float oy = p.getYaw(), op = p.getPitch();
            Rotations.sendLook(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center));
            boolean slam = false;
            if (cfg.stunSlam && shieldUp && axe >= 0) {
                InvHelper.select(p, axe);
                c.interactionManager.attackEntity(p, t);
                p.swingHand(Hand.MAIN_HAND);
                slam = true;
                shieldUp = false;   // bolta zarbasi qalqonni sindiradi
            }
            if (cfg.spearMelee && !slam && !shieldUp && spear >= 0 && lungeReady(c, p, cfg)) {
                InvHelper.select(p, spear);
                c.interactionManager.attackWithPiercingWeapon(p.getMainHandStack().get(DataComponentTypes.PIERCING_WEAPON));
                p.swingHand(Hand.MAIN_HAND);
                last = "nayza jab";
            } else {
                int w = cfg.mace && p.fallDistance >= 1.5 ? maceSlot(p, t) : -1;
                if (w < 0) w = find(p, "_sword");
                if (w < 0) w = axe;
                if (w < 0) w = maceSlot(p, t);
                if (w >= 0) InvHelper.select(p, w);
                c.interactionManager.attackEntity(p, t);
                p.swingHand(Hand.MAIN_HAND);
                last = String.format("%s%s %.1f", slam ? "stun slam, " : "",
                        p.getMainHandStack().isOf(Items.MACE) ? "mace" : "zarba", p.fallDistance);
            }
            if (user) {
                p.setYaw(oy);
                p.setPitch(op);
            }
            return;
        }

        boolean aimed = Rotations.turn(p, Rotations.yawTo(eye, center), Rotations.pitchTo(eye, center));
        if (cfg.spearMelee && spear >= 0 && !shieldUp) {
            if (aimed) InvHelper.select(p, spear);
            if (aimed && lungeReady(c, p, cfg)) {
                c.interactionManager.attackWithPiercingWeapon(p.getMainHandStack().get(DataComponentTypes.PIERCING_WEAPON));
                p.swingHand(Hand.MAIN_HAND);
                status = "nayza jab";
            } else {
                status = "nayza kutish";
            }
            return;
        }
        int weapon = cfg.axeShield && shieldUp && axe >= 0 ? axe : find(p, "_sword");
        if (weapon < 0) weapon = axe;
        if (weapon < 0) weapon = maceSlot(p, t);
        if (weapon < 0) weapon = spear;
        if (weapon < 0) return;
        if (p.getInventory().getSelectedSlot() != weapon) {
            InvHelper.select(p, weapon);
            status = "qurol";
            return;
        }
        if (aimed && p.getAttackCooldownProgress(0.5f) >= 0.92f) {
            c.interactionManager.attackEntity(p, t);
            p.swingHand(Hand.MAIN_HAND);
            status = shieldUp ? "bolta (qalqon)" : "urish";
        }
    }

    private static boolean lungeReady(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        ItemStack st = p.getMainHandStack();
        if (!st.contains(DataComponentTypes.PIERCING_WEAPON)) return false;
        if (p.hasVehicle() || p.isGliding() || p.isTouchingWater()) return false;
        if (!p.getAbilities().creativeMode && p.getHungerManager().getFoodLevel() < cfg.minFood) return false;
        if (p.isBelowMinimumAttackCharge(st, 0) || p.getAttackCooldownProgress(0.0f) < 1.0f) return false;
        return !c.interactionManager.isFlyingLocked();
    }

    // ------------------------------------------------------------ ovqat
    private void startEat(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        LivingEntity t = target;
        if (cfg.retreatPearl && t != null && p.distanceTo(t) < 10) {
            Vec3d away = new Vec3d(p.getX() - t.getX(), 0, p.getZ() - t.getZ());
            if (away.lengthSquared() < 1e-3) away = new Vec3d(1, 0, 0);
            Vec3d spot = p.getEntityPos().add(away.normalize().multiply(cfg.retreatDist));
            int gy = TravelController.groundY(c.world, spot.x, spot.z);
            if (PearlHelper.throwAt(c, p, new Vec3d(spot.x, gy, spot.z))) {
                say(p, "§eAuto PvP: qochib ovqat yeyapmiz");
            }
        }
        eatCount = -1;
        set(Phase.EAT);
    }

    private void eat(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        status = "ovqat";
        int slot = foodSlot(p);
        ItemStack s = slot >= 0 ? p.getInventory().getStack(slot) : ItemStack.EMPTY;
        boolean eaten = eatCount >= 0 && (slot < 0 || s.getCount() < eatCount);
        if (slot < 0 || eaten || phaseTicks > 100) {
            c.options.useKey.setPressed(false);
            set(Phase.GROUND);
            return;
        }
        if (slot >= 9) {   // inventardan hotbar'ga (E orqali)
            InvHelper.swap(c, p, slot, p.getInventory().getSelectedSlot());
            return;
        }
        if (eatCount < 0) eatCount = s.getCount();
        InvHelper.select(p, slot);
        c.options.useKey.setPressed(true);
    }

    /** Yeyish mumkin bo'lgan eng yaxshi ovqat (avval oltin olma). Hotbar yoki inventar. */
    private static int foodSlot(ClientPlayerEntity p) {
        int best = -1, bestScore = -1;
        boolean hungry = p.getHungerManager().isNotFull();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            FoodComponent f = s.get(DataComponentTypes.FOOD);
            if (f == null || (!hungry && !f.canAlwaysEat())) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (id.contains("rotten") || id.contains("spider_eye") || id.contains("pufferfish") || id.contains("chorus")
                    || id.contains("suspicious")) continue;
            int score = (s.isOf(Items.ENCHANTED_GOLDEN_APPLE) ? 1000 : s.isOf(Items.GOLDEN_APPLE) ? 500 : 0)
                    + f.nutrition() * 10 + (i < 9 ? 5 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ yordamchi
    /** Pastga qarab wind charge (qarash faqat paketda), shu tickning o'zida eski slotga qaytib. */
    private static void windJump(MinecraftClient c, ClientPlayerEntity p, int wind, float yaw) {
        int original = p.getInventory().getSelectedSlot();
        float oy = p.getYaw(), op = p.getPitch();
        InvHelper.select(p, wind);
        p.setYaw(yaw);
        p.setPitch(90.0f);
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        p.setYaw(oy);
        p.setPitch(op);
        InvHelper.select(p, original);
    }

    /** Hotbar'dagi item (cooldown'da bo'lmagan). */
    private static int hotbar(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item) && !p.getItemCooldownManager().isCoolingDown(s)) return i;
        }
        return -1;
    }

    private static boolean ready(ClientPlayerEntity p, Item item) {
        return hotbar(p, item) >= 0;
    }

    /** Eng yaxshi (material bo'yicha) qurol: id oxiri "_sword" yoki "_axe". */
    private static int find(ClientPlayerEntity p, String suffix) {
        int best = -1, bestRank = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty() || (s.isDamageable() && s.willBreakNextUse())) continue;
            String path = Registries.ITEM.getId(s.getItem()).getPath();
            if (!path.endsWith(suffix)) continue;
            int r = rank(path) * 10 + level(s, "sharpness");
            if (r > bestRank) {
                bestRank = r;
                best = i;
            }
        }
        return best;
    }

    /** Eng yuqori Lunge darajali nayza. */
    private static int spearSlot(ClientPlayerEntity p) {
        int best = -1, bestLvl = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.contains(DataComponentTypes.PIERCING_WEAPON) || (s.isDamageable() && s.willBreakNextUse())) continue;
            int lvl = level(s, "lunge");
            if (lvl > bestLvl) {
                bestLvl = lvl;
                best = i;
            }
        }
        return best;
    }

    /** Zirhli nishonga Breach, aks holda Density; bo'lmasa istalgan mace. */
    private static int maceSlot(ClientPlayerEntity p, LivingEntity target) {
        boolean armored = target.getArmor() >= 12;
        int best = -1, bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.MACE)) continue;
            int score = 1 + (armored ? level(s, "breach") * 3 + level(s, "density") : level(s, "density") * 3 + level(s, "breach"));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static int rank(String path) {
        if (path.startsWith("netherite")) return 6;
        if (path.startsWith("diamond")) return 5;
        if (path.startsWith("iron")) return 4;
        if (path.startsWith("copper")) return 3;
        if (path.startsWith("stone")) return 2;
        if (path.startsWith("golden")) return 1;
        return 0;
    }

    private static int level(ItemStack s, String name) {
        for (RegistryEntry<Enchantment> e : s.getEnchantments().getEnchantments()) {
            if (e.getKey().map(k -> k.getValue().getPath().equals(name)).orElse(false)) {
                return s.getEnchantments().getLevel(e);
            }
        }
        return 0;
    }

    private static boolean inReach(ClientPlayerEntity p, LivingEntity t, PvpConfig cfg) {
        return p.getEyePos().distanceTo(closest(t, p.getEyePos())) <= cfg.reachTenths / 10.0;
    }

    // ------------------------------------------------------------ nishon
    private LivingEntity pick(MinecraftClient c, ClientPlayerEntity p, PvpConfig cfg) {
        if (target != null && valid(p, target, cfg) && target.distanceTo(p) <= cfg.range * 1.3) return target;
        LivingEntity best = null;
        double bestD = cfg.range;
        for (LivingEntity e : c.world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(cfg.range),
                x -> valid(p, x, cfg))) {
            double d = e.distanceTo(p);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best != null && best != target && cfg.messages) {
            p.sendMessage(Text.literal("§cAuto PvP: " + best.getName().getString()), true);
        }
        return best;
    }

    private static boolean valid(ClientPlayerEntity p, LivingEntity e, PvpConfig cfg) {
        if (e == null || e == p || !e.isAlive() || e.isRemoved() || e.distanceTo(p) > cfg.range) return false;
        if (e instanceof PlayerEntity pl) {
            if (!cfg.players || pl.isSpectator() || pl.isCreative()) return false;
            return !(cfg.ignoreFriends && isFriend(pl));
        }
        return cfg.mobs && e instanceof Monster;
    }

    private static boolean isFriend(PlayerEntity t) {
        String name = t.getName().getString();
        for (String f : AutoTntConfig.get().friends) if (f.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static Vec3d closest(LivingEntity t, Vec3d from) {
        var b = t.getBoundingBox();
        return new Vec3d(MathHelper.clamp(from.x, b.minX, b.maxX), MathHelper.clamp(from.y, b.minY, b.maxY),
                MathHelper.clamp(from.z, b.minZ, b.maxZ));
    }

    private static double horizontal(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------ tugmalar (sizniki ustun)
    /** Haqiqatan (klaviatura/sichqonchada) bosib turilganmi. */
    private static boolean held(MinecraftClient c, KeyBinding kb) {
        InputUtil.Key k = KeyBindingHelper.getBoundKeyOf(kb);
        if (k.getCode() < 0) return false;
        if (k.getCategory() == InputUtil.Type.KEYSYM) return InputUtil.isKeyPressed(c.getWindow(), k.getCode());
        if (k.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(c.getWindow().getHandle(), k.getCode()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private static boolean userMoving(MinecraftClient c) {
        GameOptions o = c.options;
        return held(c, o.forwardKey) || held(c, o.backKey) || held(c, o.leftKey) || held(c, o.rightKey)
                || held(c, o.jumpKey);
    }

    /** Biz tugma bosganmizmi (boshqa modullarning tugmalariga tegmaslik uchun). */
    private boolean ours = false;

    private void press(KeyBinding kb) {
        kb.setPressed(true);
        ours = true;
    }

    /** O'tgan tickda biz bosgan tugmalarni qo'yib yuboramiz - faqat siz bosib turmaganlarini. */
    private void releaseOurs(MinecraftClient c) {
        if (!ours) return;
        ours = false;
        GameOptions o = c.options;
        for (KeyBinding kb : new KeyBinding[] {o.forwardKey, o.sprintKey}) {
            if (kb.isPressed() && !held(c, kb)) kb.setPressed(false);
        }
    }

    private static void say(ClientPlayerEntity p, String msg) {
        if (PvpConfig.get().messages) p.sendMessage(Text.literal(msg), true);
    }

    public String hudLine(MinecraftClient c) {
        PvpConfig cfg = PvpConfig.get();
        if (c.player == null || !cfg.enabled || !cfg.hud) return null;
        String t = target == null ? "§7-" : "§f" + target.getName().getString();
        return "§cAuto PvP: " + t + " §7" + status + (last != null ? " §8| " + last : "");
    }
}
