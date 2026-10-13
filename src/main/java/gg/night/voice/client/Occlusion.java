package gg.night.voice.client;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/** Client-side environment analysis: wall muffling (low-pass) and enclosure (reverb). */
public final class Occlusion {
    private Occlusion() {}

    /** 0 = clear line of sight, 1 = fully buried behind thick/hard blocks. */
    public static float measure(World w, Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        if (len < 1.0) return 0f;
        int steps = (int) (len * 2);
        float sum = 0f;
        BlockPos last = null;
        for (int i = 1; i < steps; i++) {
            BlockPos bp = BlockPos.ofFloored(from.add(d.multiply(i / (double) steps)));
            if (bp.equals(last)) continue;
            last = bp;
            BlockState s = w.getBlockState(bp);
            if (s.isOpaqueFullCube(w, bp)) {
                // stone (res 6) ~0.5, obsidian (res 1200) = 1.0
                sum += 0.35f + Math.min(0.65f, s.getBlock().getBlastResistance() / 40f);
            }
        }
        return MathHelper.clamp(sum / 3f, 0f, 1f);
    }

    /** 0 = open air, up to 1 = closed cave / cell. Uses 14 rays on a sphere around the listener. */
    public static float enclosure(World w, Entity src, Vec3d eye) {
        final int n = 14;
        final double max = 12.0;
        int hits = 0;
        double total = 0;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i / (double) (n - 1)) * 2;
            double r = Math.sqrt(1 - y * y);
            double th = golden * i;
            Vec3d dir = new Vec3d(Math.cos(th) * r, y, Math.sin(th) * r);
            HitResult hr = w.raycast(new RaycastContext(eye, eye.add(dir.multiply(max)),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, src));
            if (hr.getType() == HitResult.Type.BLOCK) {
                hits++;
                total += hr.getPos().distanceTo(eye);
            }
        }
        float closed = hits / (float) n;
        float avg = hits > 0 ? (float) (total / hits) : (float) max;
        float amt = closed * closed * MathHelper.clamp(avg / 8f, 0.3f, 1f);
        if (w.isSkyVisible(BlockPos.ofFloored(eye))) amt *= 0.2f;
        return MathHelper.clamp(amt, 0f, 1f);
    }
}
