package uz.automine;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

public class AutoMineClient implements ClientModInitializer {
    public static KeyBinding keyToggle;   // tugma berilmagan, ClickGUI'da sozlanadi
    public static final MineController CONTROLLER = new MineController();

    @Override
    public void onInitializeClient() {
        AutoMineConfig.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("automine", "main"));
        keyToggle = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.automine.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        // /mine area <radius> | /mine start <ruda1> [ruda2] ... | /mine stop | /mine sell <buyruq>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("mine")
                        .then(ClientCommandManager.literal("area")
                                .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> {
                                            int r = IntegerArgumentType.getInteger(ctx, "radius");
                                            MinecraftClient.getInstance().execute(() -> CONTROLLER.setArea(r));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("start")
                                .then(ClientCommandManager.argument("ores", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String[] ores = StringArgumentType.getString(ctx, "ores").trim().split("\\s+");
                                            MinecraftClient.getInstance().execute(() -> CONTROLLER.start(Arrays.asList(ores)));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            MinecraftClient.getInstance().execute(() -> CONTROLLER.stop("§eAuto Mine: to'xtatildi"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("sell")
                                .then(ClientCommandManager.argument("buyruq", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String cmd = StringArgumentType.getString(ctx, "buyruq").trim();
                                            if (cmd.startsWith("/")) cmd = cmd.substring(1);
                                            AutoMineConfig.get().sellCommand = cmd;
                                            AutoMineConfig.save();
                                            ctx.getSource().sendFeedback(Text.literal("§aAuto Mine: sotish buyrug'i - /" + cmd));
                                            return 1;
                                        })))));

        ClientTickEvents.START_CLIENT_TICK.register(CONTROLLER::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("automine", "status"), AutoMineClient::renderHud);
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.options.hudHidden) return;
        String line = CONTROLLER.hudLine();
        if (line == null) return;
        int x = ctx.getScaledWindowWidth() - c.textRenderer.getWidth(line) - 6;
        ctx.drawTextWithShadow(c.textRenderer, Text.literal(line), x, 138, 0xFFFFFFFF);
    }
}
