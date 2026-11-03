package dev.itemdrop;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.ShaderProgramKeys;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ItemDropMod implements ClientModInitializer {

    static boolean enabled = true;
    static int speed = 3; // 1..5

    static class DropPath {
        final List<Vec3d> points = new ArrayList<>();
        int rgb;
    }

    record Death(Vec3d pos, long tick) {}

    static final List<DropPath> paths = new ArrayList<>();
    static final List<Death> deaths = new ArrayList<>();
    static final Set<Integer> deadSeen = new HashSet<>();
    static final Map<Integer, Long> firstSeen = new HashMap<>();
    static final Set<Integer> tracked = new HashSet<>();
    static long tickCount;
    static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Item Drop Menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "Item Drop"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new Menu());
            }
            tick(client);
        });
        WorldRenderEvents.LAST.register(ItemDropMod::render);
    }

    static void tick(MinecraftClient mc) {
        paths.clear();
        if (mc.world == null || mc.player == null) {
            deaths.clear(); deadSeen.clear(); firstSeen.clear(); tracked.clear();
            return;
        }
        if (!enabled) return;
        tickCount++;

        Set<Integer> deadNow = new HashSet<>();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof PlayerEntity p && p.isDead()) {
                deadNow.add(p.getId());
                if (deadSeen.add(p.getId())) deaths.add(new Death(p.getPos(), tickCount));
            }
        }
        deadSeen.retainAll(deadNow);
        deaths.removeIf(d -> tickCount - d.tick() > 60);

        Set<Integer> present = new HashSet<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity item)) continue;
            int id = item.getId();
            present.add(id);
            long first = firstSeen.computeIfAbsent(id, k -> tickCount);
            if (!tracked.contains(id) && tickCount - first <= 6) {
                for (Death d : deaths) {
                    if (d.pos().squaredDistanceTo(item.getPos()) < 36.0) { tracked.add(id); break; }
                }
            }
            if (tracked.contains(id) && !item.isOnGround() && paths.size() < 64) {
                DropPath p = simulate(mc, item);
                if (p.points.size() > 1) paths.add(p);
            }
        }
        firstSeen.keySet().retainAll(present);
        tracked.retainAll(present);
    }

    static DropPath simulate(MinecraftClient mc, ItemEntity item) {
        DropPath path = new DropPath();
        path.rgb = MathHelper.hsvToRgb((item.getId() * 0.137f) % 1f, 0.75f, 1f);
        Vec3d pos = item.getPos();
        Vec3d vel = item.getVelocity();
        path.points.add(pos);
        for (int i = 0; i < 240; i++) {
            if (!item.hasNoGravity()) vel = vel.add(0, -0.04, 0);
            Vec3d next = pos.add(vel);
            var hit = mc.world.raycast(new RaycastContext(pos, next,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, item));
            if (hit.getType() != HitResult.Type.MISS) { path.points.add(hit.getPos()); break; }
            pos = next;
            path.points.add(pos);
            vel = vel.multiply(0.98, 0.98, 0.98);
            if (pos.y < mc.world.getBottomY()) break;
        }
        return path;
    }

    static void render(WorldRenderContext ctx) {
        if (!enabled || paths.isEmpty()) return;
        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;
        Vec3d cam = ctx.camera().getPos();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.RENDERTYPE_LINES);
        RenderSystem.lineWidth(2.0f);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
        MatrixStack.Entry en = matrices.peek();
        double t = (System.nanoTime() / 1_000_000_000.0) * (20.0 * speed * 0.5);

        for (DropPath p : paths) {
            List<Vec3d> pts = p.points;
            int segs = pts.size() - 1;
            if (segs < 1) continue;
            int r = (p.rgb >> 16) & 255, g = (p.rgb >> 8) & 255, b = p.rgb & 255;

            for (int i = 0; i < segs; i++) {
                Vec3d a = pts.get(i).subtract(cam), c = pts.get(i + 1).subtract(cam);
                line(en, buf, a.x, a.y, a.z, c.x, c.y, c.z, r, g, b, 150);
            }

            Vec3d land = pts.get(segs).subtract(cam);
            double x = land.x, y = land.y + 0.02, z = land.z, h = 0.25;
            line(en, buf, x - h, y, z - h, x + h, y, z - h, r, g, b, 255);
            line(en, buf, x + h, y, z - h, x + h, y, z + h, r, g, b, 255);
            line(en, buf, x + h, y, z + h, x - h, y, z + h, r, g, b, 255);
            line(en, buf, x - h, y, z + h, x - h, y, z - h, r, g, b, 255);

            double idx = t % segs;
            int i0 = (int) idx;
            double f = idx - i0;
            int i1 = Math.min(i0 + 1, segs - 1);
            Vec3d s0 = pts.get(i0).lerp(pts.get(i0 + 1), f).subtract(cam);
            Vec3d s1 = pts.get(i1).lerp(pts.get(i1 + 1), f).subtract(cam);
            line(en, buf, s0.x, s0.y, s0.z, s1.x, s1.y, s1.z, 255, 255, 255, 255);
        }

        BuiltBuffer built = buf.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);

        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    static void line(MatrixStack.Entry e, VertexConsumer vc, double x1, double y1, double z1,
                     double x2, double y2, double z2, int r, int g, int b, int a) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = MathHelper.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        vc.vertex(e, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(e, nx, ny, nz);
        vc.vertex(e, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(e, nx, ny, nz);
    }

    static class Menu extends Screen {
        Menu() { super(Text.literal("Item Drop")); }

        static Text label() { return Text.literal("Item Drop: " + (enabled ? "ON" : "OFF")); }

        @Override
        protected void init() {
            int cx = width / 2, y = height / 2 - 20;
            addDrawableChild(ButtonWidget.builder(label(), b -> {
                enabled = !enabled;
                b.setMessage(label());
            }).dimensions(cx - 100, y, 200, 20).build());
            addDrawableChild(new SpeedSlider(cx - 100, y + 24, 200, 20));
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            super.render(ctx, mx, my, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 42, 0xFFFFFFFF);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_G) { close(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    static class SpeedSlider extends SliderWidget {
        SpeedSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (speed - 1) / 4.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() { setMessage(Text.literal("Speed: " + speed)); }

        @Override
        protected void applyValue() {
            speed = 1 + (int) Math.round(value * 4.0);
            value = (speed - 1) / 4.0;
        }
    }
              }
