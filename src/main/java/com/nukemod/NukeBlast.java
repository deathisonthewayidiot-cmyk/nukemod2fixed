package com.nukemod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One nuclear detonation in progress.
 *
 * <p>Instead of a single vanilla explosion, the blast plays out over a few seconds:
 * <ul>
 *   <li><b>Crater:</b> a perfect sphere of {@link #CRATER_RADIUS} blocks is carved out by an
 *       expanding shockwave front (shell by shell, {@link #SHOCKWAVE_SPEED} blocks per tick), so
 *       the world edit is spread over many ticks rather than one big lag spike.</li>
 *   <li><b>Shockwave:</b> an expanding ring along the ground plus two dome rings of dust/cloud.</li>
 *   <li><b>Fireball:</b> a rising ball of flame and smoke.</li>
 *   <li><b>Mushroom cloud:</b> a smoke stem and a rolling, expanding cap that rises for
 *       {@link #CLOUD_TICKS} ticks.</li>
 * </ul>
 *
 * Everything is server side and uses vanilla particles, so no client code is needed.
 * Particles are flagged to ignore the usual 32 block limit so the cloud is visible from far away.
 */
public final class NukeBlast {

	// ---------------------------------------------------------------------------------------
	// Tuning knobs. All lengths are in blocks, all times in ticks (20 ticks = 1 second).
	// ---------------------------------------------------------------------------------------

	/** Radius of the spherical crater. Bigger = laggier. Cost grows with the cube of this. */
	public static final int CRATER_RADIUS = 48;

	/** How fast the shockwave front expands. The crater is dug by this front. */
	public static final double SHOCKWAVE_SPEED = 2.0;

	/** The visible shockwave keeps travelling past the crater until it reaches this radius. */
	public static final double SHOCKWAVE_MAX_RADIUS = 96.0;

	/**
	 * Damage and knockback strength for entities (vanilla TNT is 4.0). Damage reaches roughly
	 * twice this many blocks. This explosion does NOT break blocks; the crater does that.
	 */
	public static final float DAMAGE_POWER = 48.0F;

	/** How long the mushroom cloud keeps rising and growing. */
	public static final int CLOUD_TICKS = 240;

	/** Final height of the middle of the cap above ground zero. */
	public static final double CLOUD_HEIGHT = 100.0;

	/** Final radius of the cap (measured to the middle of its ring). */
	public static final double CAP_RADIUS = 34.0;

	/** Fireball lifetime. */
	private static final int FIREBALL_TICKS = 40;

	// Particles emitted per tick. Lower these if clients struggle (they cost frame rate).
	private static final int CAP_PUFFS_PER_TICK = 80;
	private static final int STEM_PUFFS_PER_TICK = 24;
	private static final int SKIRT_PUFFS_PER_TICK = 6;

	/** Share of cap puffs that go into the solid dome core instead of the rolling outer ring. */
	private static final double CAP_CORE_SHARE = 0.4;

	/** Speed of the rolling motion in the cap (blocks per tick). */
	private static final double CAP_ROLL = 0.05;

	private static final double TAU = Math.PI * 2.0;

	// ---------------------------------------------------------------------------------------

	private static final List<NukeBlast> ACTIVE = new ArrayList<>();

	private final ServerLevel level;
	private final BlockPos origin;
	private final double cx;
	private final double cy;
	private final double cz;
	private final RandomSource random;

	private int age = 0;
	/** Outer radius that has already been carved. Starts negative so the centre block is included. */
	private double carvedRadius = -1.0;

	private NukeBlast(ServerLevel level, BlockPos origin) {
		this.level = level;
		this.origin = origin.immutable();
		this.cx = origin.getX() + 0.5;
		this.cy = origin.getY() + 0.5;
		this.cz = origin.getZ() + 0.5;
		this.random = level.getRandom();
	}

	// ---------------------------------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------------------------------

	/** Detonates a nuke at {@code pos}. Must be called on the server thread. */
	public static void start(ServerLevel level, BlockPos pos) {
		level.removeBlock(pos, false);

		// A vanilla explosion with no block damage: hurts and launches entities and plays the
		// big explosion sound. The crater itself is carved by this class.
		level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, DAMAGE_POWER, Level.ExplosionInteraction.NONE);

		ACTIVE.add(new NukeBlast(level, pos));
	}

	/** Advances every running blast by one tick. Hooked to the server tick in {@link NukeMod}. */
	public static void tickAll(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		// Index loop: a blast could in theory start another one while ticking.
		for (int i = 0; i < ACTIVE.size();) {
			NukeBlast blast = ACTIVE.get(i);
			boolean done;
			if (blast.level.getServer() != server) {
				done = true; // left over from a previous world session
			} else {
				try {
					done = blast.tick();
				} catch (RuntimeException e) {
					NukeMod.LOGGER.error("Nuke blast failed and was cancelled", e);
					done = true;
				}
			}
			if (done) {
				ACTIVE.remove(i);
			} else {
				i++;
			}
		}
	}

	private boolean tick() {
		carve();
		shockwave();
		fireball();
		cloud();
		age++;
		return age >= CLOUD_TICKS;
	}

	// ---------------------------------------------------------------------------------------
	// Crater: a perfect sphere, dug outward shell by shell
	// ---------------------------------------------------------------------------------------

	private void carve() {
		double outer = Math.min((double) CRATER_RADIUS, SHOCKWAVE_SPEED * (age + 1));
		if (outer <= carvedRadius) {
			return;
		}
		double inner = carvedRadius;
		carvedRadius = outer;

		double out2 = outer * outer;
		double in2 = inner < 0.0 ? -1.0 : inner * inner;
		// The outermost layer gets full block updates so water flows, sand falls, torches pop off.
		double rim = CRATER_RADIUS - 1.0;
		double rim2 = rim * rim;

		int r = (int) Math.ceil(outer);
		int ox = origin.getX();
		int oy = origin.getY();
		int oz = origin.getZ();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int h2 = dx * dx + dz * dz;
				if (h2 > out2) {
					continue;
				}
				// Never touch (and so never force-load) chunks that are not loaded.
				if (!level.hasChunkAt(p.set(ox + dx, oy, oz + dz))) {
					continue;
				}
				int dyMax = (int) Math.floor(Math.sqrt(out2 - h2));
				for (int dy = -dyMax; dy <= dyMax; dy++) {
					int d2 = h2 + dy * dy;
					if (d2 > out2 || d2 <= in2) {
						continue;
					}
					p.set(ox + dx, oy + dy, oz + dz);
					BlockState state = level.getBlockState(p);
					// Skip air, and anything unbreakable (bedrock, barriers, command blocks...).
					if (state.isAir() || state.getDestroySpeed(level, p) < 0.0F) {
						continue;
					}
					// Vaporise block entities so chests do not spill their contents everywhere.
					if (state.hasBlockEntity()) {
						level.removeBlockEntity(p);
					}
					if (d2 >= rim2) {
						level.setBlock(p.immutable(), air, Block.UPDATE_ALL);
					} else {
						level.setBlock(p, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------
	// Visual effects
	// ---------------------------------------------------------------------------------------

	/** Expanding ring along the ground plus two dome rings, riding on the crater front. */
	private void shockwave() {
		double radius = SHOCKWAVE_SPEED * (age + 1);
		if (radius > SHOCKWAVE_MAX_RADIUS) {
			return;
		}
		double[] elevation = {0.0, 0.5, 0.95}; // radians above the ground plane
		int[] maxBlobs = {56, 32, 18};
		int[] density = {3, 2, 2};

		for (int i = 0; i < elevation.length; i++) {
			double ringRadius = radius * Math.cos(elevation[i]);
			double y = cy + 0.4 + radius * Math.sin(elevation[i]);
			int blobs = (int) Math.round(TAU * ringRadius / 6.0);
			blobs = Math.max(6, Math.min(maxBlobs[i], blobs));
			double spreadH = i == 0 ? 1.6 : 2.2;
			double spreadV = i == 0 ? 0.5 : 1.5;
			double phase = random.nextDouble() * TAU;

			for (int b = 0; b < blobs; b++) {
				double a = phase + b * TAU / blobs;
				double x = cx + ringRadius * Math.cos(a);
				double z = cz + ringRadius * Math.sin(a);
				puff(ParticleTypes.CLOUD, x, y, z, density[i], spreadH, spreadV, spreadH, 0.01);
				if (i == 0) {
					puff(ParticleTypes.LARGE_SMOKE, x, y, z, 1, spreadH, spreadV, spreadH, 0.02);
				}
			}
		}
	}

	/** The initial flash and the glowing ball that rises out of ground zero. */
	private void fireball() {
		if (age >= FIREBALL_TICKS) {
			return;
		}
		double rho = 5.0 + 15.0 * Math.sqrt((double) age / FIREBALL_TICKS);
		double fy = cy + rho * 0.6;

		if (age == 0) {
			puff(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 4.0, cz, 16, 8.0, 5.0, 8.0, 0.0);
			puff(ParticleTypes.FLAME, cx, cy + 4.0, cz, 250, 6.0, 5.0, 6.0, 0.35);
		}
		if (age < 13 && age % 3 == 1) {
			puff(ParticleTypes.EXPLOSION_EMITTER, cx, fy, cz, 6, rho * 0.6, rho * 0.4, rho * 0.6, 0.0);
		}
		puff(ParticleTypes.FLAME, cx, fy, cz, 70, rho * 0.35, rho * 0.3, rho * 0.35, 0.03);
		puff(ParticleTypes.LARGE_SMOKE, cx, fy + 2.0, cz, 30, rho * 0.45, rho * 0.35, rho * 0.45, 0.03);
		puff(ParticleTypes.LAVA, cx, fy, cz, 5, rho * 0.35, rho * 0.3, rho * 0.35, 0.0);
	}

	/** The mushroom cloud: a dust skirt at the bottom of the pit, a stem, and a rolling cap. */
	private void cloud() {
		if (age < 2) {
			return;
		}
		double h = CLOUD_HEIGHT * ease((double) age / CLOUD_TICKS);
		double rise = h - CLOUD_HEIGHT * ease((double) (age - 1) / CLOUD_TICKS);
		double capR = capRadius(age);
		double growth = (capRadius(age + 1) - capR) / capR;
		double capT = capR * 0.55; // size of the cap's rolling ring and dome
		double capY = cy + h;

		// The stem starts at the bottom of the crater (which is still being dug early on).
		double dug = Math.min((double) CRATER_RADIUS, SHOCKWAVE_SPEED * age);
		double stemBottom = cy - 0.85 * dug;
		double stemTop = capY - capT * 0.5;

		// Dust skirt boiling at the bottom of the pit.
		if (age >= 3 && age < 100) {
			for (int i = 0; i < SKIRT_PUFFS_PER_TICK; i++) {
				double ang = random.nextDouble() * TAU;
				double dist = 3.0 + 13.0 * random.nextDouble();
				double v = 0.06 + 0.10 * random.nextDouble();
				puff(ParticleTypes.CAMPFIRE_COSY_SMOKE,
						cx + dist * Math.cos(ang), stemBottom + 1.0 + 2.0 * random.nextDouble(), cz + dist * Math.sin(ang),
						0, v * Math.cos(ang), 0.02, v * Math.sin(ang), 1.0);
			}
		}

		// Stem: a column of smoke between the pit and the cap, dark near the bottom.
		for (int i = 0; i < STEM_PUFFS_PER_TICK; i++) {
			double f = random.nextDouble(); // 0 = bottom, 1 = cap
			double y = stemBottom + f * (stemTop - stemBottom);
			double rad = stemRadius(f, capR) * Math.sqrt(random.nextDouble());
			double ang = random.nextDouble() * TAU;
			ParticleOptions type = random.nextDouble() < 0.5 * (1.0 - f)
					? ParticleTypes.LARGE_SMOKE
					: ParticleTypes.CAMPFIRE_COSY_SMOKE;
			puff(type, cx + rad * Math.cos(ang), y, cz + rad * Math.sin(ang), 0, 0.0, 0.04, 0.0, 1.0);
		}

		// Cap: a solid dome core plus a rolling outer ring. Each puff drifts with the cap
		// (rising, spreading outward). On the ring it also curls: up and outward over the top,
		// down along the outer edge.
		if (age < 6) {
			return;
		}
		double heat = Math.max(0.0, 1.0 - age / 70.0); // young cap glows, then turns to smoke
		for (int i = 0; i < CAP_PUFFS_PER_TICK; i++) {
			double phi = random.nextDouble() * TAU;
			double rho; // distance from the cloud's axis
			double y;
			double b; // -1 = underside ... +1 = top, used for shading
			double roll = 0.0; // curl velocity (vertical), ring only
			double rollOut = 0.0; // curl velocity (outward), ring only

			if (random.nextDouble() < CAP_CORE_SHARE) {
				// Solid core: a point inside a dome-shaped ellipsoid sitting on top of the stem.
				double u = Math.cbrt(random.nextDouble());
				double ct = 2.0 * random.nextDouble() - 1.0;
				double st = Math.sqrt(1.0 - ct * ct);
				rho = u * st * (capR + capT * 0.3);
				y = capY + capT * 0.1 + u * ct * capT * 1.3;
				b = u * ct;
			} else {
				// Rolling ring: a point inside a torus.
				double s = Math.sqrt(random.nextDouble());
				double th = random.nextDouble() * TAU;
				double a = s * Math.cos(th); // -1 inner edge ... +1 outer edge
				b = s * Math.sin(th);
				rho = capR + a * capT;
				y = capY + b * capT;
				roll = -CAP_ROLL * a;
				rollOut = CAP_ROLL * b;
			}

			double cos = Math.cos(phi);
			double sin = Math.sin(phi);
			double radial = rho * growth + rollOut;
			double vy = rise + roll;

			double dark = 0.20 + 0.35 * (1.0 - b) / 2.0; // darker underside, lighter top
			double pick = random.nextDouble();
			ParticleOptions type;
			if (pick < heat) {
				type = ParticleTypes.FLAME;
			} else if (random.nextDouble() < dark) {
				type = ParticleTypes.LARGE_SMOKE;
			} else {
				type = ParticleTypes.CAMPFIRE_COSY_SMOKE;
			}
			puff(type, cx + rho * cos, y, cz + rho * sin, 0, radial * cos, vy, radial * sin, 1.0);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	/** Radius of the stem at height fraction f (0 = pit floor, 1 = underside of the cap). */
	private static double stemRadius(double f, double capR) {
		double base = 4.0 * Math.pow(1.0 - f, 3.0); // flares out at the bottom
		double neck = 0.25 * capR * Math.pow(f, 4.0); // flares into the cap at the top
		return 4.5 + base + neck;
	}

	private static double capRadius(int t) {
		return 4.0 + (CAP_RADIUS - 4.0) * ease(t / (CLOUD_TICKS * 0.7));
	}

	/** Fast start, slow finish. Input and output are in 0..1. */
	private static double ease(double u) {
		double c = Math.max(0.0, Math.min(1.0, u));
		double v = 1.0 - c;
		return 1.0 - v * v;
	}

	/**
	 * Sends particles to every player within 512 blocks.
	 *
	 * <p>With {@code count > 0}: {@code count} particles scattered around the point with a
	 * gaussian spread of {@code dx, dy, dz} blocks and random velocity scaled by {@code speed}.
	 * With {@code count == 0}: one particle at the point moving at velocity
	 * {@code (dx, dy, dz) * speed}.
	 */
	private void puff(ParticleOptions type, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
		level.sendParticles(type, true, false, x, y, z, count, dx, dy, dz, speed);
	}
}
