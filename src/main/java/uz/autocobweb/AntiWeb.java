package uz.autocobweb;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import uz.spearlunger.InvHelper;
import uz.spearlunger.PearlHelper;
import uz.spearlunger.Rotations;

/**
 * Anti-web: o'zingiz cobweb ichida qolsangiz - suv paqirini tanlab ipga suv quyadi (ip yo'q bo'ladi), keyingi tickda
 * suvni qaytarib oladi va eski slotga qaytadi. Suv bo'lmasa (yoki Nether'da) va sozlamada ruxsat berilgan bo'lsa,
 * qarab turgan tomonga ender pearl otib o'zi ipdan chiqib ketadi (hech narsa sindirmaydi/kesmaydi - ip hech qachon
 * qilich bilan kesilmaydi, bu juda sekin va shubhali ko'rinadi). Ikkisi ham bo'lmasa hech narsa qilmaydi - o'zingiz
 * yurib chiqguningizcha kutadi. Qarash faqat paketlarda o'zgaradi - kamera qimirlamaydi.
 */
class AntiWeb {
    private enum Phase { IDLE, PICKUP }

    private Phase phase = Phase.IDLE;
    private BlockPos web = null;
    private int slot = -1, original = -1, ticks = 0;
    private int[] borrowed = null;
    /** Pearl otilgandan keyin tushib bo'lgunicha yana otmaslik uchun. */
    private int pearlCooldown = 0;

    /** true = shu tick band (ipdan chiqyapmiz), boshqa amal qilinmasin. */
    boolean tick(MinecraftClient c, ClientPlayerEntity p, CobwebConfig cfg) {
        ClientWorld w = c.world;
        if (pearlCooldown > 0) pearlCooldown--;

        if (phase == Phase.PICKUP) {
            ticks++;
            BlockPos src = waterSource(w, web);
            if (src != null && p.getInventory().getStack(slot).isOf(Items.BUCKET)) {
                use(c, p, slot, Items.BUCKET, Vec3d.ofCenter(src));
                finish(c, p);
            } else if (ticks > 10) {
                finish(c, p);
            }
            return true;
        }

        BlockPos feet = p.getBlockPos();
        BlockPos target = isWeb(w, feet) ? feet : isWeb(w, feet.up()) ? feet.up() : null;
        if (target == null) return false;

        boolean nether = w.getRegistryKey() == World.NETHER;   // u yerda suv bug'lanadi
        if (!nether) {
            int bucket = prepare(c, p, Items.WATER_BUCKET);
            if (bucket >= 0) {
                web = target;
                original = p.getInventory().getSelectedSlot();
                ticks = 0;
                slot = bucket;
                use(c, p, bucket, Items.WATER_BUCKET, Vec3d.ofCenter(target));
                phase = Phase.PICKUP;
                say(p, "§bAnti-web: suv");
                return true;
            }
        }

        if (cfg.antiWebPearl && pearlCooldown <= 0) {
            Vec3d escape = escapePoint(w, p);
            if (escape != null && PearlHelper.throwAt(c, p, escape)) {
                pearlCooldown = 60;
                say(p, "§bAnti-web: pearl");
                return true;
            }
        }
        return false;   // suv ham, pearl ham yo'q - hech narsa qilmaymiz, o'zingiz chiqib ketguningizcha kutamiz
    }

    /** Slotni tanlab (haqiqiy tanlov - serverga ham xabar beradi), o'sha nuqtaga qarab ishlatadi, qarashni qaytaradi. */
    private void use(MinecraftClient c, ClientPlayerEntity p, int s, Item expected, Vec3d at) {
        float yaw = p.getYaw(), pitch = p.getPitch();
        Vec3d eye = p.getEyePos();
        InvHelper.select(p, s);
        if (!p.getMainHandStack().isOf(expected)) return;   // hali qo'lga kelmagan - keyingi tickda qaytadan urinamiz
        p.setYaw(Rotations.yawTo(eye, at));
        p.setPitch(Rotations.pitchTo(eye, at));
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    private void finish(MinecraftClient c, ClientPlayerEntity p) {
        if (original >= 0) InvHelper.select(p, original);
        if (borrowed != null && InvHelper.returnItems()) InvHelper.swap(c, p, borrowed[0], borrowed[1]);
        borrowed = null;
        phase = Phase.IDLE;
        web = null;
        slot = -1;
        original = -1;
    }

    /** Hotbar sloti; inventarda bo'lsa vaqtincha hotbar'ga olib keladi. */
    private int prepare(MinecraftClient c, ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(item)) return i;
        for (int i = 9; i < 36; i++) {
            if (!p.getInventory().getStack(i).isOf(item)) continue;
            int sel = p.getInventory().getSelectedSlot();
            int target = sel == 8 ? 7 : 8;
            for (int h = 0; h < 9; h++) if (h != sel && p.getInventory().getStack(h).isEmpty()) { target = h; break; }
            InvHelper.swap(c, p, i, target);
            borrowed = new int[] { i, target };
            return target;
        }
        return -1;
    }

    /** Qarab turgan tomondan, devordan/bo'shliqdan bir oz narida, yerga tortib pearl tushadigan nuqta. */
    private static Vec3d escapePoint(ClientWorld w, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        float pitch = Math.max(-30f, Math.min(30f, p.getPitch()));
        Vec3d dir = Vec3d.fromPolar(pitch, p.getYaw());
        Vec3d far = eye.add(dir.multiply(6));
        BlockHitResult hit = w.raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p));
        Vec3d end = hit.getType() == HitResult.Type.BLOCK ? hit.getPos().subtract(dir.multiply(0.6)) : far;
        BlockPos col = BlockPos.ofFloored(end.x, eye.y, end.z);
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos ground = col.add(0, dy - 1, 0);
            BlockPos feet = ground.up();
            if (!w.getBlockState(ground).isSideSolidFullSquare(w, ground, Direction.UP)) continue;
            if (isWeb(w, feet) || isWeb(w, feet.up())) continue;
            if (!w.getBlockState(feet).getCollisionShape(w, feet).isEmpty()) continue;
            if (!w.getFluidState(feet).isEmpty()) continue;   // lavaga/suvga tushmasin
            return Vec3d.ofCenter(feet);
        }
        return null;
    }

    private static BlockPos waterSource(ClientWorld w, BlockPos around) {
        if (isSource(w, around)) return around;
        for (Direction d : Direction.values()) {
            if (isSource(w, around.offset(d))) return around.offset(d);
        }
        return null;
    }

    private static boolean isSource(ClientWorld w, BlockPos pos) {
        FluidState fs = w.getFluidState(pos);
        return fs.isStill() && fs.isOf(Fluids.WATER);
    }

    private static boolean isWeb(ClientWorld w, BlockPos pos) {
        return w.getBlockState(pos).isOf(Blocks.COBWEB);
    }

    private static void say(ClientPlayerEntity p, String msg) {
        if (CobwebConfig.get().messages) p.sendMessage(Text.literal(msg), true);
    }
}
