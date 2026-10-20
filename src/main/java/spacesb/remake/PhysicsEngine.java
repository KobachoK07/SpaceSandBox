package spacesb.remake;

import org.joml.Vector3d;

public class PhysicsEngine {

    public static final double G = 0.00019926;
    public static final double MAX_C_BLOCKS = 237.9305;

    public static void tick(double dtReal) {
        if (!SpaceEngine.isPaused && SpaceEngine.physicsEnabled) {
            long tickStart = System.nanoTime();

            double dtSim = dtReal * SpaceEngine.timeScale;

            int subSteps = (int) Math.max(1, Math.min(5000, Math.abs(dtSim) / 10.0));
            double subDt = dtSim / subSteps;

            for (int i = 0; i < subSteps; i++) {
                updatePhysics(subDt);
            }

            updateParticles(dtReal, dtSim);
            SpaceEngine.simTimeSeconds += dtSim;

            SpaceEngine.lastPhysicsTickMs = (System.nanoTime() - tickStart) / 1000000.0;
        }
    }

    private static void updatePhysics(double dt) {
        java.util.List<SpaceEngine.Planet> planets = SpaceEngine.planets;
        int size = planets.size();
        if (size == 0) return;

        Vector3d[] accelerations = new Vector3d[size];
        for (int i = 0; i < size; i++) accelerations[i] = new Vector3d();

        if (SpaceEngine.enableGravity) {
            for (int i = 0; i < size; i++) {
                SpaceEngine.Planet p1 = planets.get(i);
                if (p1.mass <= 0) continue;

                for (int j = i + 1; j < size; j++) {
                    SpaceEngine.Planet p2 = planets.get(j);
                    if (p2.mass <= 0) continue;

                    Vector3d dir = new Vector3d(p2.pos).sub(p1.pos);
                    double distSq = dir.lengthSquared();
                    double dist = Math.sqrt(distSq);

                    double sumRad = p1.radius + p2.radius;
                    double effDist = Math.max(dist, sumRad * 0.5);

                    if (effDist < 0.05) effDist = 0.05;

                    dir.normalize();

                    Vector3d vRel = new Vector3d(p1.vel).sub(p2.vel);
                    double vSq = vRel.lengthSquared();
                    double cSq = MAX_C_BLOCKS * MAX_C_BLOCKS;
                    double relativityFactor = 1.0 + 3.0 * (vSq / cSq);

                    double f = (G / (effDist * effDist)) * relativityFactor;

                    Vector3d a1 = new Vector3d(dir).mul(f * p2.mass);
                    Vector3d a2 = new Vector3d(dir).mul(-f * p1.mass);

                    if (!Double.isNaN(a1.x)) accelerations[i].add(a1);
                    if (!Double.isNaN(a2.x)) accelerations[j].add(a2);
                }
            }
        }

        for (int i = 0; i < size; i++) {
            SpaceEngine.Planet p = planets.get(i);
            p.vel.add(new Vector3d(accelerations[i]).mul(dt));

            double speed = p.vel.length();
            if (speed > MAX_C_BLOCKS) p.vel.normalize().mul(MAX_C_BLOCKS);

            Vector3d delta = new Vector3d(p.vel).mul(dt);
            p.pos.add(delta);

            if (p.rotationPeriod > 0.0001) {
                double rotSpeed = 360.0 / (p.rotationPeriod * 3600.0);
                p.currentAngle = (p.currentAngle + rotSpeed * dt) % 360.0;
            }

            double minDistSq = (p.radius * 1.5) * (p.radius * 1.5);
            if (p.trail.isEmpty()) {
                p.trail.add(new Vector3d(p.pos));
            } else {
                Vector3d lastNode = p.trail.get(p.trail.size() - 1);
                if (lastNode.distanceSquared(p.pos) > minDistSq) {
                    p.trail.add(new Vector3d(p.pos));
                    if (p.trail.size() > 300) p.trail.remove(0);
                }
            }
        }

        if (SpaceEngine.enableCollisions) {
            for (int i = size - 1; i >= 0; i--) {
                if (i >= planets.size()) continue;
                SpaceEngine.Planet p1 = planets.get(i);
                if (p1.mass <= 0) continue;

                for (int j = i - 1; j >= 0; j--) {
                    if (j >= planets.size()) continue;
                    SpaceEngine.Planet p2 = planets.get(j);
                    if (p2.mass <= 0) continue;

                    Vector3d dir = new Vector3d(p2.pos).sub(p1.pos);
                    double dist = dir.length();
                    double sumRad = p1.radius + p2.radius;

                    if (dist < sumRad) {
                        SpaceEngine.Planet bigger = p1.mass >= p2.mass ? p1 : p2;
                        SpaceEngine.Planet smaller = p1.mass < p2.mass ? p1 : p2;

                        double oldMass = smaller.mass;
                        double transferRate = oldMass * 2.0 * dt;
                        double overlap = Math.max(0, sumRad - dist);
                        transferRate *= (1.0 + (overlap / smaller.radius) * 10.0);

                        if (transferRate > smaller.mass) transferRate = smaller.mass;

                        if (transferRate > 0) {
                            Vector3d pMom = new Vector3d(bigger.vel).mul(bigger.mass);
                            Vector3d tMom = new Vector3d(smaller.vel).mul(transferRate);
                            bigger.vel = pMom.add(tMom).div(bigger.mass + transferRate);

                            bigger.mass += transferRate;
                            smaller.mass -= transferRate;

                            double volTransfer = Math.pow(smaller.radius, 3) * (transferRate / oldMass);
                            bigger.targetRadius = Math.pow(Math.pow(bigger.targetRadius, 3) + volTransfer, 1.0 / 3.0);

                            smaller.targetRadius = smaller.targetRadius * Math.pow((smaller.mass / oldMass), 1.0 / 3.0);
                            smaller.radius = smaller.targetRadius;

                            Vector3d pullDir = new Vector3d(bigger.pos).sub(smaller.pos);
                            if (pullDir.lengthSquared() > 0.001) {
                                smaller.vel.lerp(bigger.vel, Math.min(1.0, 5.0 * dt));
                                smaller.pos.add(pullDir.normalize().mul(dist * 3.0 * dt));
                            }

                            Vector3d pushDir = new Vector3d(bigger.pos).sub(smaller.pos).normalize();
                            Vector3d contactPoint = new Vector3d(smaller.pos).add(new Vector3d(pushDir).mul(smaller.radius));

                            int burstCount = 20 + (int)(Math.random() * 20);
                            for(int b = 0; b < burstCount; b++) {
                                if (SpaceEngine.particles.size() >= 8000) break;
                                Vector3d off = new Vector3d(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5);
                                if(off.lengthSquared() > 0.001) off.normalize();
                                Vector3d pVel = new Vector3d(off).mul(Math.random() * 20.0 + 5.0).add(smaller.vel);
                                int pr = 255, pg = 120 + (int)(Math.random()*80), pb = (int)(Math.random()*40);
                                SpaceEngine.particles.add(new SpaceEngine.Particle(
                                        new Vector3d(contactPoint), pVel,
                                        bigger.radius * 0.03 + Math.random()*0.02,
                                        3.0 + Math.random()*3.0, pr, pg, pb, 0, transferRate * 0.01
                                ));
                            }

                            if (smaller.isRigid || bigger.isRigid) {
                                int fragCount = 5 + (int)(Math.random() * 10);
                                for(int d = 0; d < fragCount; d++) {
                                    if(SpaceEngine.fragments.size() < 6000) {
                                        Vector3d off = new Vector3d(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5);
                                        if(off.lengthSquared() > 0.001) off.normalize();
                                        Vector3d pVel = new Vector3d(off).mul(Math.random()*15.0 + 5.0).add(smaller.vel);
                                        float px = (float)Math.floor(Math.random() * 16.0f); float py = (float)Math.floor(Math.random() * 16.0f);
                                        SpaceEngine.fragments.add(new SpaceEngine.Fragment(
                                                new Vector3d(contactPoint), pVel, smaller.radius * (Math.random()*0.15 + 0.05), 5.0 + Math.random()*10.0, transferRate * 0.05, SpaceEngine.TEX_FRAGMENT, px/16.0f, py/16.0f, (px+1)/16.0f, (py+1)/16.0f
                                        ));
                                    }
                                }
                            }

                            Vector3d worldNormDirect = new Vector3d(smaller.pos).sub(bigger.pos);
                            if (worldNormDirect.lengthSquared() > 0.001) {
                                worldNormDirect.normalize();
                                double rot = bigger.currentAngle;

                                org.joml.Vector3f localNormF = new org.joml.Vector3f((float)worldNormDirect.x, (float)worldNormDirect.y, (float)worldNormDirect.z);
                                org.joml.Matrix4f invRot = new org.joml.Matrix4f().rotateZ((float)Math.toRadians(bigger.tilt)).rotateY((float)Math.toRadians(-rot)).invert();
                                invRot.transformDirection(localNormF);
                                localNormF.normalize();

                                Vector3d localNorm = new Vector3d(localNormF.x, localNormF.y, localNormF.z);

                                double absX = Math.abs(localNorm.x);
                                double absY = Math.abs(localNorm.y);
                                double absZ = Math.abs(localNorm.z);

                                if (absX >= absY && absX >= absZ) {
                                    localNorm.set(Math.signum(localNorm.x), 0, 0);
                                } else if (absY >= absX && absY >= absZ) {
                                    localNorm.set(0, Math.signum(localNorm.y), 0);
                                } else {
                                    localNorm.set(0, 0, Math.signum(localNorm.z));
                                }

                                double targetDecalSize = smaller.radius * 1.5;
                                if (targetDecalSize > bigger.targetRadius * 0.3) targetDecalSize = bigger.targetRadius * 0.3;

                                boolean found = false;
                                for (SpaceEngine.Scorch s : bigger.scorches) {
                                    if (s.localNorm.dot(localNorm) > 0.85) {
                                        s.size = Math.max(s.size, targetDecalSize);
                                        s.life = 180.0;
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found && Math.random() < 15.0 * dt) {
                                    bigger.scorches.add(new SpaceEngine.Scorch(localNorm, targetDecalSize, 180.0));
                                }
                            }
                        }

                        if (smaller.mass < 1e-6 || smaller.radius < bigger.targetRadius * 0.05) {
                            smaller.mass = 0;
                        }
                    }
                }
            }
        }

        planets.removeIf(p -> p.mass <= 0);

        if (SpaceEngine.enableCentrifugalForce) {
            for (int i = planets.size() - 1; i >= 0; i--) {
                SpaceEngine.Planet p = planets.get(i);

                if (Math.abs(p.radius - p.targetRadius) > 0.001) {
                    p.radius += (p.targetRadius - p.radius) * 15.0 * dt;
                }

                if (SpaceEngine.enableCentrifugalForce && !p.type.equals("Hole")) {
                    double rotPeriod = Math.max(0.0001, p.rotationPeriod);
                    double w_current = (2.0 * Math.PI) / (rotPeriod * 3600.0);
                    double w_crit = Math.sqrt((G * p.mass) / Math.pow(p.radius, 3));

                    if (w_current > w_crit) {
                        double excess = (w_current - w_crit) / w_crit;

                        p.rotationPeriod *= (1.0 + Math.min(excess, 2.0) * 0.8 * dt);

                        double shedMass = p.mass * 0.5 * dt * excess;
                        if (shedMass > p.mass * 0.2) shedMass = p.mass * 0.2;

                        if (shedMass > 1e-6) {
                            double massRatio = (p.mass - shedMass) / p.mass;
                            p.mass -= shedMass;

                            double exponent = p.isRigid ? (1.0 / 2.2) : (1.0 / 3.0);
                            p.targetRadius = p.targetRadius * Math.pow(massRatio, exponent);
                            p.radius = p.targetRadius;

                            double idealMass = p.mass * (p.isRigid ? 0.005 : 0.002);
                            double expectedSpawns = shedMass / idealMass;

                            int count = (int) expectedSpawns;
                            if (Math.random() < (expectedSpawns - count)) count++;

                            if (count > 0) {
                                for (int k = 0; k < count; k++) {
                                    double angle = Math.random() * Math.PI * 2;
                                    double h = (Math.random() - 0.5) * 0.3;

                                    org.joml.Vector3f offsetF = new org.joml.Vector3f((float)Math.cos(angle), (float)h, (float)Math.sin(angle)).normalize().mul((float)p.radius);
                                    offsetF.rotateZ((float)Math.toRadians(p.tilt));
                                    Vector3d offset = new Vector3d(offsetF.x, offsetF.y, offsetF.z);
                                    Vector3d pPos = new Vector3d(p.pos).add(offset);

                                    org.joml.Vector3f spinVelF = new org.joml.Vector3f(0, 1, 0).rotateZ((float)Math.toRadians(p.tilt)).cross(offsetF).normalize().mul((float)(w_current * p.radius));
                                    Vector3d spinVel = new Vector3d(spinVelF.x, spinVelF.y, spinVelF.z);
                                    Vector3d pVel = new Vector3d(p.vel).add(spinVel).add(new Vector3d(offset).normalize().mul(Math.random() * 2.0));

                                    int pr = 255, pg = 120 + (int)(Math.random() * 80), pb = (int)(Math.random() * 40);

                                    if (p.isRigid) {
                                        if (SpaceEngine.fragments.size() < 6000) {
                                            float px = (float)Math.floor(Math.random() * 16.0f); float py = (float)Math.floor(Math.random() * 16.0f);
                                            double fragSize = p.radius * (Math.random() * 0.06 + 0.02);
                                            SpaceEngine.fragments.add(new SpaceEngine.Fragment(pPos, pVel, fragSize, 10.0 + Math.random() * 15.0, idealMass, SpaceEngine.TEX_FRAGMENT, px / 16.0f, py / 16.0f, (px + 1) / 16.0f, (py + 1) / 16.0f));
                                        }
                                        if (SpaceEngine.particles.size() < 8000) {
                                            SpaceEngine.particles.add(new SpaceEngine.Particle(pPos, pVel, p.radius * 0.04 + Math.random() * 0.04, 5.0 + Math.random() * 5.0, pr, pg, pb, 0, idealMass * 0.1));
                                        }
                                    } else {
                                        if (SpaceEngine.particles.size() < 8000) {
                                            SpaceEngine.particles.add(new SpaceEngine.Particle(pPos, pVel, p.radius * 0.05 + Math.random() * 0.1, 5.0 + Math.random() * 5.0, pr, pg, pb, 0, idealMass));
                                        }
                                    }
                                }
                            }
                        } else if (p.mass < 1e-5) {
                            explodePlanet(p, null);
                            p.mass = 0;
                        }
                    }
                }
            }
        }

        if (SpaceEngine.enableRocheLimit) {
            for (int i = planets.size() - 1; i >= 0; i--) {
                SpaceEngine.Planet small = planets.get(i);
                if (small.type.equals("Hole") || small.mass <= 0) continue;

                for (SpaceEngine.Planet massive : planets) {
                    if (small == massive || massive.mass <= small.mass * 2.0) continue;

                    Vector3d dir = new Vector3d(massive.pos).sub(small.pos);
                    double dist = dir.length();

                    double rhoMassive = massive.getDensity();
                    double rhoSmall = small.getDensity();

                    if (rhoSmall > 0 && rhoMassive > 0) {
                        double rocheConstant = small.isRigid ? 1.26 : 2.44;
                        double rocheLimit = rocheConstant * massive.radius * Math.pow(rhoMassive / rhoSmall, 1.0 / 3.0);

                        if (dist < rocheLimit) {
                            double depth = 1.0 - (dist / rocheLimit);
                            double shedMass = small.mass * (0.1 + depth * 0.9) * dt;
                            if (shedMass > small.mass * 0.2) shedMass = small.mass * 0.2;

                            if (shedMass > 1e-6) {
                                double massRatio = (small.mass - shedMass) / small.mass;
                                small.mass -= shedMass;

                                double exponent = small.isRigid ? (1.0 / 2.2) : (1.0 / 3.0);
                                small.targetRadius = small.targetRadius * Math.pow(massRatio, exponent);
                                small.radius = small.targetRadius;

                                double idealMass = small.mass * (small.isRigid ? 0.005 : 0.002);
                                double expectedSpawns = shedMass / idealMass;

                                int count = (int) expectedSpawns;
                                if (Math.random() < (expectedSpawns - count)) count++;

                                if (count > 0) {
                                    for (int k = 0; k < count; k++) {
                                        Vector3d pullDir = new Vector3d(massive.pos).sub(small.pos).normalize();
                                        pullDir.add(new Vector3d(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5).mul(0.6)).normalize();

                                        Vector3d offset = new Vector3d(pullDir).mul(small.radius);
                                        Vector3d pPos = new Vector3d(small.pos).add(offset);
                                        Vector3d pVel = new Vector3d(small.vel).add(new Vector3d(pullDir).mul(Math.random() * 5.0 + 2.0));

                                        int pr = 255, pg = 120 + (int)(Math.random() * 80), pb = (int)(Math.random() * 40);

                                        if (small.isRigid) {
                                            if (SpaceEngine.fragments.size() < 6000) {
                                                float px = (float)Math.floor(Math.random() * 16.0f); float py = (float)Math.floor(Math.random() * 16.0f);
                                                double fragSize = small.radius * (Math.random() * 0.06 + 0.02);
                                                SpaceEngine.fragments.add(new SpaceEngine.Fragment(pPos, pVel, fragSize, 10.0 + Math.random() * 15.0, idealMass, SpaceEngine.TEX_FRAGMENT, px / 16.0f, py / 16.0f, (px + 1) / 16.0f, (py + 1) / 16.0f));
                                            }
                                            if (SpaceEngine.particles.size() < 8000) {
                                                SpaceEngine.particles.add(new SpaceEngine.Particle(pPos, pVel, small.radius * 0.04 + Math.random() * 0.04, 5.0 + Math.random() * 5.0, pr, pg, pb, 0, idealMass * 0.1));
                                            }
                                        } else {
                                            if (SpaceEngine.particles.size() < 8000) {
                                                SpaceEngine.particles.add(new SpaceEngine.Particle(pPos, pVel, small.radius * 0.05 + Math.random() * 0.1, 5.0 + Math.random() * 5.0, pr, pg, pb, 0, idealMass));
                                            }
                                        }
                                    }
                                }
                            } else if (small.mass < 1e-5) {
                                explodePlanet(small, massive);
                                small.mass = 0;
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    private static void updateParticles(double dtReal, double dtSim) {
        java.util.List<SpaceEngine.Particle> particles = SpaceEngine.particles;
        java.util.List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
        java.util.List<SpaceEngine.Planet> planets = SpaceEngine.planets;

        for (int i = particles.size() - 1; i >= 0; i--) {
            SpaceEngine.Particle pt = particles.get(i);
            pt.life -= dtReal;
            if (pt.life <= 0) { particles.remove(i); continue; }

            Vector3d accel = new Vector3d();
            boolean absorbed = false;
            if (SpaceEngine.enableGravity) {
                for (SpaceEngine.Planet pl : planets) {
                    Vector3d dir = new Vector3d(pl.pos).sub(pt.pos);
                    double distSq = dir.lengthSquared();
                    if(distSq < 0.0001) continue;
                    double dist = Math.sqrt(distSq);

                    if (SpaceEngine.enableCollisions && dist < pl.radius) { absorbed = true; break; }

                    double effDist = Math.max(dist, pl.radius * 0.5);
                    double f = G * pl.mass / (effDist * effDist);
                    accel.add(dir.normalize().mul(f));
                }
            }
            if (absorbed) { particles.remove(i); continue; }
            pt.vel.add(new Vector3d(accel).mul(dtSim));
            if (pt.vel.length() > MAX_C_BLOCKS) pt.vel.normalize().mul(MAX_C_BLOCKS);
            pt.pos.add(new Vector3d(pt.vel).mul(dtSim));
        }

        for (int i = fragments.size() - 1; i >= 0; i--) {
            SpaceEngine.Fragment f1 = fragments.get(i);
            f1.life -= dtReal;
            if (f1.life <= 0) { fragments.remove(i); continue; }

            Vector3d accel = new Vector3d();
            boolean absorbed = false;

            if (SpaceEngine.enableGravity) {
                for (SpaceEngine.Planet pl : planets) {
                    Vector3d dir = new Vector3d(pl.pos).sub(f1.pos);
                    double distSq = dir.lengthSquared();
                    if(distSq < 0.0001) continue;
                    double dist = Math.sqrt(distSq);

                    if (SpaceEngine.enableCollisions && dist < pl.radius) {
                        absorbed = true;

                        int burstCount = 15 + (int)(Math.random() * 15);
                        for(int b = 0; b < burstCount; b++) {
                            if (SpaceEngine.particles.size() >= 8000) break;
                            Vector3d off = new Vector3d(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5);
                            if(off.lengthSquared() > 0.001) off.normalize();
                            Vector3d pVel = new Vector3d(off).mul(Math.random() * 15.0 + 5.0).add(pl.vel);
                            int pr = 255, pg = 120 + (int)(Math.random()*80), pb = (int)(Math.random()*40);
                            SpaceEngine.particles.add(new SpaceEngine.Particle(
                                    new Vector3d(f1.pos), pVel,
                                    pl.radius * 0.02 + Math.random()*0.02,
                                    3.0 + Math.random()*3.0, pr, pg, pb, 0, f1.mass * 0.01
                            ));
                        }

                        Vector3d worldNormDirect = new Vector3d(f1.pos).sub(pl.pos);
                        if (worldNormDirect.lengthSquared() > 0.001) {
                            worldNormDirect.normalize();
                            double rot = pl.currentAngle;
                            org.joml.Vector3f localNormF = new org.joml.Vector3f((float)worldNormDirect.x, (float)worldNormDirect.y, (float)worldNormDirect.z);
                            org.joml.Matrix4f invRot = new org.joml.Matrix4f().rotateZ((float)Math.toRadians(pl.tilt)).rotateY((float)Math.toRadians(-rot)).invert();
                            invRot.transformDirection(localNormF);
                            localNormF.normalize();
                            Vector3d localNorm = new Vector3d(localNormF.x, localNormF.y, localNormF.z);

                            double absX = Math.abs(localNorm.x);
                            double absY = Math.abs(localNorm.y);
                            double absZ = Math.abs(localNorm.z);
                            if (absX >= absY && absX >= absZ) {
                                localNorm.set(Math.signum(localNorm.x), 0, 0);
                            } else if (absY >= absX && absY >= absZ) {
                                localNorm.set(0, Math.signum(localNorm.y), 0);
                            } else {
                                localNorm.set(0, 0, Math.signum(localNorm.z));
                            }

                            double targetDecalSize = Math.max(f1.size * 5.0, pl.radius * 0.05);
                            if (targetDecalSize > pl.targetRadius * 0.25) targetDecalSize = pl.targetRadius * 0.25;

                            boolean found = false;
                            for (SpaceEngine.Scorch s : pl.scorches) {
                                if (s.localNorm.dot(localNorm) > 0.85) {
                                    s.size = Math.max(s.size, targetDecalSize);
                                    s.life = 180.0;
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                pl.scorches.add(new SpaceEngine.Scorch(localNorm, targetDecalSize, 180.0));
                            }
                        }

                        double volPlanet = Math.pow(pl.targetRadius, 3);
                        double volFrag = Math.pow(f1.size, 3);
                        pl.mass += f1.mass;
                        pl.targetRadius = Math.pow(volPlanet + volFrag, 1.0 / 3.0);
                        pl.radius = pl.targetRadius;
                        break;
                    }

                    double effDist = Math.max(dist, pl.radius * 0.5);
                    double frc = G * pl.mass / (effDist * effDist);
                    accel.add(dir.normalize().mul(frc));
                }
            }

            if (absorbed) { fragments.remove(i); continue; }

            f1.vel.add(new Vector3d(accel).mul(dtSim));
            if (f1.vel.length() > MAX_C_BLOCKS) f1.vel.normalize().mul(MAX_C_BLOCKS);
            f1.pos.add(new Vector3d(f1.vel).mul(dtSim));
            f1.angle += f1.rotSpeed * dtReal;
        }

        for (SpaceEngine.Planet p : SpaceEngine.planets) {
            for (int i = p.scorches.size() - 1; i >= 0; i--) {
                SpaceEngine.Scorch s = p.scorches.get(i);
                s.life -= dtReal;
                if (s.life <= 0) p.scorches.remove(i);
            }
        }
    }

    public static void spawnContinuousSparks(Vector3d contactPoint, Vector3d impactVel, double size, double dt) {
        if (SpaceEngine.particles.size() > 8000) return;

        int count = (int) (150 * dt);
        if (Math.random() < (150 * dt) % 1) count++;

        for (int i = 0; i < count; i++) {
            Vector3d off = new Vector3d(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5);
            if(off.lengthSquared() > 0.001) off.normalize();

            Vector3d pVel = new Vector3d(off).mul(Math.random() * 25.0 + 5.0).add(impactVel);

            int pr = 255, pg = 150 + (int)(Math.random() * 105), pb = (int)(Math.random() * 50);

            SpaceEngine.particles.add(new SpaceEngine.Particle(
                    contactPoint, pVel,
                    Math.random() * size * 0.1 + 0.05,
                    0.5 + Math.random()*1.5,
                    pr, pg, pb, 0, 0
            ));
        }
    }

    public static void explodePlanet(SpaceEngine.Planet p, SpaceEngine.Planet attractor) {
        int num = (int) Math.max(100, Math.min(2000, p.radius * 120));

        org.joml.Vector3f spinAxisF = new org.joml.Vector3f(0, 1, 0).rotateZ((float)Math.toRadians(p.tilt));
        Vector3d spinAxis = new Vector3d(spinAxisF.x, spinAxisF.y, spinAxisF.z);

        double rotSec = Math.max(0.0001, p.rotationPeriod) * 3600.0;
        double omega = (2.0 * Math.PI / rotSec);

        double massPerParticle = p.mass / num;

        for(int i=0; i<num; i++) {
            double angle = Math.random() * Math.PI * 2;
            double h = (Math.random() - 0.5) * 0.9;

            org.joml.Vector3f offF = new org.joml.Vector3f((float)Math.cos(angle), (float)h, (float)Math.sin(angle)).normalize().mul((float)(Math.random() * p.radius));
            offF.rotateZ((float)Math.toRadians(p.tilt));
            Vector3d off = new Vector3d(offF.x, offF.y, offF.z);

            Vector3d vRot = new Vector3d(spinAxis).cross(off).mul(omega);
            Vector3d pVel = new Vector3d(p.vel).add(vRot);

            pVel.add(new Vector3d(off).normalize().mul(Math.random() * 20.0 + 5.0));

            if (!Double.isNaN(pVel.x)) {
                if (p.isRigid) {
                    if (SpaceEngine.fragments.size() < 6000) {
                        float px = (float)Math.floor(Math.random() * 16.0f);
                        float py = (float)Math.floor(Math.random() * 16.0f);
                        double fSize = p.radius * (Math.random() * 0.06 + 0.02);
                        double fLife = 10.0 + Math.random() * 15.0;
                        SpaceEngine.fragments.add(new SpaceEngine.Fragment(
                                new Vector3d(p.pos).add(off), pVel, fSize, fLife, massPerParticle, SpaceEngine.TEX_FRAGMENT, px/16.0f, py/16.0f, (px+1)/16.0f, (py+1)/16.0f
                        ));
                    }
                    if (SpaceEngine.particles.size() < 8000) {
                        int pr = 255, pg = 120 + (int)(Math.random() * 80), pb = (int)(Math.random() * 40);
                        SpaceEngine.particles.add(new SpaceEngine.Particle(
                                new Vector3d(p.pos).add(off), pVel, p.radius * 0.04 + Math.random() * 0.04, 5.0 + Math.random() * 5.0, pr, pg, pb, 0, massPerParticle * 0.1
                        ));
                    }
                } else {
                    if (SpaceEngine.particles.size() > 8000) continue;
                    int pr = 255; int pg = 100 + (int)(Math.random() * 100); int pb = (int)(Math.random() * 30);
                    double pSize = p.radius * (Math.random() * 0.6 + 0.4);
                    double pLife = 5.0 + Math.random() * 8.0;
                    SpaceEngine.particles.add(new SpaceEngine.Particle(
                            new Vector3d(p.pos).add(off), pVel, pSize, pLife, pr, pg, pb, 0, massPerParticle
                    ));
                }
            }
        }
    }
}