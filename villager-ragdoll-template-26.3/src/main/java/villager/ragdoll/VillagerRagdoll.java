import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager; // may be npc.villager.Villager, let IntelliJ autoimport
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class VillagerRagdollMod implements ModInitializer {

    private static class Thrown {
        final Villager v;
        Vec3 lastVel = Vec3.ZERO;
        int age = 0, bounces = 0;
        Thrown(Villager v) { this.v = v; }
    }

    private static final List<Thrown> THROWN = new ArrayList<>();

    private static void blood(ServerLevel level, Villager v, int count) {
        level.sendParticles(new DustParticleOptions(0xAA0000, 1.3f),
                v.getX(), v.getY() + 0.8, v.getZ(), count, 0.35, 0.4, 0.35, 0.15);
    }

    @Override
    public void onInitialize() {
        // Right-click a villager with an empty hand to throw it
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(entity instanceof Villager v) || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;

            Vec3 look = player.getLookAngle();
            v.setNoAi(true); // stops it walking around mid-flight
            v.setDeltaMovement(look.x * 1.6, look.y * 1.6 + 0.4, look.z * 1.6);
            v.hurtMarked = true; // syncs the velocity to clients
            THROWN.add(new Thrown(v));
            level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 1f, 1f);
            return InteractionResult.SUCCESS;
        });

        // Fake ragdoll: spin in the air, bounce and bleed on impact
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Thrown> it = THROWN.iterator();
            while (it.hasNext()) {
                Thrown t = it.next();
                Villager v = t.v;
                ServerLevel level = (ServerLevel) v.level();

                if (!v.isAlive()) {
                    blood(level, v, 60); // big burst on death
                    it.remove();
                    continue;
                }

                t.age++;
                v.setYRot(v.getYRot() + 35f); // spin

                boolean hit = t.age > 3 && (v.onGround() || v.horizontalCollision);
                if (hit) {
                    double speed = t.lastVel.length();
                    if (speed > 0.5 && t.bounces < 4) {
                        v.hurtServer(level, level.damageSources().flyIntoWall(), (float) (speed * 6));
                        blood(level, v, 25);
                        level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 1f, 0.8f);
                        v.setDeltaMovement(t.lastVel.x * 0.4, speed * 0.5, t.lastVel.z * 0.4);
                        v.hurtMarked = true;
                        t.bounces++;
                    } else {
                        v.setNoAi(false); // landed, back to normal
                        it.remove();
                        continue;
                    }
                }

                if (t.age > 200) { v.setNoAi(false); it.remove(); continue; }
                t.lastVel = v.getDeltaMovement();
            }
        });
    }
}
