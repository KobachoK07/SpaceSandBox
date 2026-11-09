package spacesb.remake;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

public class CameraManager {
    public static final Vector3d pos = new Vector3d(0, 150, 0);
    public static final Vector3f rot = new Vector3f(45, 0, 0);

    public static SpaceEngine.Planet lockedTarget = null;
    public static boolean isLocked = false;

    private static boolean isRotating = false;
    private static double lastMouseX = 0, lastMouseY = 0;
    private static boolean firstMouse = true;

    private static double lastTime = GLFW.glfwGetTime();
    private static double lastLeftClickTime = 0;
    private static double lastKeyTime = GLFW.glfwGetTime();

    private static final Vector3d SCRATCH_FWD = new Vector3d();
    private static final Vector3d SCRATCH_RAW_FWD = new Vector3d();
    private static final Vector3d SCRATCH_RIGHT = new Vector3d();
    private static final Vector3d SCRATCH_PUSH = new Vector3d();
    private static final Vector3d SCRATCH_TARGET = new Vector3d();
    private static final Vector3d SCRATCH_REL = new Vector3d();

    private static final Vector3f SCRATCH_RAY_OUT = new Vector3f();
    private static final Vector3f SCRATCH_RAW_RAY_OUT = new Vector3f();
    private static final float[] SCRATCH_UV = new float[2];
    private static final int[] SCRATCH_W = new int[1], SCRATCH_H = new int[1];
    private static final double[] SCRATCH_MX = new double[1], SCRATCH_MY = new double[1];
    private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_VIEW = new Matrix4f();

    public static boolean isRotating() { return isRotating; }

    public static Matrix4f getProjectionMatrix() {
        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        if (window != 0) GLFW.glfwGetFramebufferSize(window, SCRATCH_W, SCRATCH_H);
        float aspect = (SCRATCH_H[0] > 0) ? (float) SCRATCH_W[0] / (float) SCRATCH_H[0] : (16.0f / 9.0f);
        return SCRATCH_PROJ.setPerspective((float) Math.toRadians(70), aspect, 0.1f, Float.POSITIVE_INFINITY);
    }

    public static Matrix4f getViewMatrix() {
        return SCRATCH_VIEW.identity()
                .rotateX((float) Math.toRadians(rot.x))
                .rotateY((float) Math.toRadians(rot.y))
                .translate((float) -pos.x, (float) -pos.y, (float) -pos.z);
    }

    public static void ensureFitsOnScreen(double planetRadius) {
        double requiredHeight = planetRadius * 3.6;
        if (pos.y < requiredHeight) {
            double pitch = Math.toRadians(rot.x), yaw = Math.toRadians(rot.y);
            SCRATCH_FWD.set(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)).normalize();
            double pullBack = (requiredHeight - pos.y) / Math.max(0.25, Math.abs(SCRATCH_FWD.y));
            pos.fma(-pullBack, SCRATCH_FWD);
            if (pos.y < requiredHeight) pos.y = requiredHeight;
        }
        double minComfortableSpeed = planetRadius * 0.5;
        if (SpaceEngine.playerFlightSpeed < minComfortableSpeed) {
            SpaceEngine.playerFlightSpeed = Math.min(1000000.0, minComfortableSpeed);
        }
    }

    public static void update(SpaceEngine.Planet currentHologram) {
        double currentTime = GLFW.glfwGetTime();
        double dt = currentTime - lastTime;
        lastTime = currentTime;

        if (SpaceEngine.isPlacingPlanet && !isLocked && SpaceEngine.menuItems != null
                && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length
                && SpaceEngine.currentSpawnMode != SpaceEngine.SpawnMode.LAUNCH) {
            SpaceEngine.MenuItem item = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex];
            Vector3d holoPos = SpaceEngine.getHologramPos(item.radius);
            double requiredDist = item.radius * 4.2;
            double currentDist = pos.distance(holoPos);

            if (currentDist < requiredDist) {
                SCRATCH_PUSH.set(pos).sub(holoPos);
                if (SCRATCH_PUSH.lengthSquared() > 1e-6) SCRATCH_PUSH.normalize();
                else SCRATCH_PUSH.set(0.0, 1.0, 0.0);

                SCRATCH_TARGET.set(holoPos).fma(requiredDist, SCRATCH_PUSH);
                pos.lerp(SCRATCH_TARGET, Math.min(1.0, dt * 12.0));
                if (pos.y < item.radius * 1.5) pos.y = item.radius * 1.5;
            }
        }

        if (isLocked && lockedTarget != null) {
            if (!SpaceEngine.planets.contains(lockedTarget)) {
                isLocked = false;
                lockedTarget = null;
            } else {
                double minSafeDist = lockedTarget.radius * 1.85 + 0.5;
                if (lockedTarget != SpaceEngine.prevLockedTarget || SpaceEngine.targetOrbitDistance < 0) {
                    SpaceEngine.lockedOrbitDistance = Math.max(pos.distance(lockedTarget.pos), minSafeDist);
                    SpaceEngine.targetOrbitDistance = lockedTarget.radius * 4.0;
                    SpaceEngine.prevLockedTarget = lockedTarget;
                }
                if (SpaceEngine.targetOrbitDistance < minSafeDist) {
                    SpaceEngine.targetOrbitDistance = minSafeDist;
                }
                SpaceEngine.lockedOrbitDistance += (SpaceEngine.targetOrbitDistance - SpaceEngine.lockedOrbitDistance) * Math.min(1.0, 15.0 * dt);

                double pitch = Math.toRadians(rot.x), yaw = Math.toRadians(rot.y);
                SCRATCH_FWD.set(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)).normalize();
                pos.set(lockedTarget.pos).fma(-SpaceEngine.lockedOrbitDistance, SCRATCH_FWD);
            }
        } else {
            SpaceEngine.prevLockedTarget = null;
            SpaceEngine.targetOrbitDistance = -1.0;
            SpaceEngine.lockedOrbitDistance = -1.0;
        }

        resolvePlanetCollisions();
    }

    public static void resolvePlanetCollisions() {
        for (int i = 0, sz = SpaceEngine.planets.size(); i < sz; i++) {
            SpaceEngine.Planet p = SpaceEngine.planets.get(i);
            double r = p.radius;
            double boxExtent = r + Math.max(0.35, r * 0.12);

            SCRATCH_REL.set(pos).sub(p.pos);
            if (SCRATCH_REL.lengthSquared() > 3.1 * boxExtent * boxExtent) continue;

            double tiltRad = Math.toRadians(p.tilt), rotRad = Math.toRadians(p.currentAngle);
            SCRATCH_REL.rotateZ(-tiltRad).rotateY(-rotRad);

            if (Math.abs(SCRATCH_REL.x) < boxExtent && Math.abs(SCRATCH_REL.y) < boxExtent && Math.abs(SCRATCH_REL.z) < boxExtent) {
                double dx = boxExtent - Math.abs(SCRATCH_REL.x);
                double dy = boxExtent - Math.abs(SCRATCH_REL.y);
                double dz = boxExtent - Math.abs(SCRATCH_REL.z);

                if (dx <= dy && dx <= dz) SCRATCH_REL.x = (SCRATCH_REL.x >= 0.0) ? boxExtent : -boxExtent;
                else if (dy <= dx && dy <= dz) SCRATCH_REL.y = (SCRATCH_REL.y >= 0.0) ? boxExtent : -boxExtent;
                else SCRATCH_REL.z = (SCRATCH_REL.z >= 0.0) ? boxExtent : -boxExtent;

                SCRATCH_REL.rotateY(rotRad).rotateZ(tiltRad);
                pos.set(p.pos).add(SCRATCH_REL);
            }
        }
    }

    public static void handleLeftClick() {
        double currentTime = GLFW.glfwGetTime();
        if (currentTime - lastLeftClickTime < 0.35) {
            SpaceEngine.Planet hit = getRaycastHit();
            if (hit != null) {
                isLocked = true;
                lockedTarget = hit;
            }
        } else if (isLocked && getRaycastHit() == null) {
            isLocked = false;
            lockedTarget = null;
        }
        lastLeftClickTime = currentTime;
    }

    public static SpaceEngine.Planet getRaycastHit() {
        Vector3f rawRayF = getRawMouseRay();
        SCRATCH_RAW_FWD.set(rawRayF.x, rawRayF.y, rawRayF.z);

        double pitchD = Math.toRadians(rot.x), yawD = Math.toRadians(rot.y);
        SCRATCH_FWD.set(-Math.sin(yawD) * Math.cos(pitchD), -Math.sin(pitchD), -Math.cos(yawD) * Math.cos(pitchD)).normalize();
        SCRATCH_RIGHT.set(SCRATCH_FWD).cross(0, 1, 0).normalize();
        SCRATCH_PUSH.set(SCRATCH_RIGHT).cross(SCRATCH_FWD).normalize();

        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        if (window != 0) {
            GLFW.glfwGetWindowSize(window, SCRATCH_W, SCRATCH_H);
            GLFW.glfwGetCursorPos(window, SCRATCH_MX, SCRATCH_MY);
        }
        if (SCRATCH_W[0] <= 0 || SCRATCH_H[0] <= 0) { SCRATCH_W[0] = 1920; SCRATCH_H[0] = 1080; }
        float uv0 = (float) ((SCRATCH_MX[0] - 0.5 * SCRATCH_W[0]) / SCRATCH_H[0]);
        float uv1 = (float) ((SCRATCH_MY[0] - 0.5 * SCRATCH_H[0]) / SCRATCH_H[0]);

        SpaceEngine.Planet hit = null;
        double minDist = Double.MAX_VALUE;

        for (int i = 0, sz = SpaceEngine.planets.size(); i < sz; i++) {
            SpaceEngine.Planet p = SpaceEngine.planets.get(i);
            if (p == null || p.mass <= 0.0) continue;
            SCRATCH_REL.set(p.pos).sub(pos);
            double distToCenter = SCRATCH_REL.length();

            boolean isHole = "Hole".equals(p.type);
            Vector3d testRay;
            if (isHole || SpaceEngine.shaderMode >= 2) {
                testRay = SCRATCH_RAW_FWD;
            } else {
                double pViewZ = SCRATCH_REL.dot(SCRATCH_FWD);
                SCRATCH_UV[0] = uv0;
                SCRATCH_UV[1] = uv1;
                applyLensWarpToUv(SCRATCH_UV, pViewZ, pos, SCRATCH_FWD, SCRATCH_RIGHT, SCRATCH_PUSH);
                SCRATCH_TARGET.set(
                        SCRATCH_FWD.x + SCRATCH_UV[0] * SCRATCH_RIGHT.x - SCRATCH_UV[1] * SCRATCH_PUSH.x,
                        SCRATCH_FWD.y + SCRATCH_UV[0] * SCRATCH_RIGHT.y - SCRATCH_UV[1] * SCRATCH_PUSH.y,
                        SCRATCH_FWD.z + SCRATCH_UV[0] * SCRATCH_RIGHT.z - SCRATCH_UV[1] * SCRATCH_PUSH.z
                ).normalize();
                testRay = SCRATCH_TARGET;
            }

            double b = SCRATCH_REL.dot(testRay);
            if (b <= 0) continue;

            double pickRadius = isHole
                    ? Math.max(p.radius * 2.25, distToCenter * 0.028)
                    : Math.max(p.radius * 1.45, distToCenter * 0.018);
            double c = SCRATCH_REL.lengthSquared() - pickRadius * pickRadius;
            double det = b * b - c;
            if (det >= 0) {
                double dist = Math.max(0.0, b - Math.sqrt(det));
                if (dist < minDist) {
                    minDist = dist;
                    hit = p;
                }
            }
        }
        return hit;
    }

    public static int deleteDebrisAlongRay() {
        Vector3f rayDirF = getMouseRay();
        SCRATCH_FWD.set(rayDirF.x, rayDirF.y, rayDirF.z);
        int removedCount = 0;

        int fWrite = 0;
        for (int i = 0, sz = SpaceEngine.fragments.size(); i < sz; i++) {
            SpaceEngine.Fragment f = SpaceEngine.fragments.get(i);
            if (f == null || f.life <= 0.0) continue;
            SCRATCH_REL.set(f.pos).sub(pos);
            double distToCenter = SCRATCH_REL.length();
            double b = SCRATCH_REL.dot(SCRATCH_FWD);
            if (b > 0) {
                double pickRadius = Math.max(f.size * 1.85, distToCenter * 0.022);
                if (SCRATCH_REL.lengthSquared() - b * b <= pickRadius * pickRadius) {
                    f.life = -1.0;
                    removedCount++;
                    continue;
                }
            }
            if (fWrite != i) SpaceEngine.fragments.set(fWrite, f);
            fWrite++;
        }
        if (fWrite < SpaceEngine.fragments.size()) {
            SpaceEngine.fragments.subList(fWrite, SpaceEngine.fragments.size()).clear();
        }

        int pWrite = 0;
        for (int i = 0, sz = SpaceEngine.particles.size(); i < sz; i++) {
            SpaceEngine.Particle pt = SpaceEngine.particles.get(i);
            if (pt == null || pt.life <= 0.0) continue;
            SCRATCH_REL.set(pt.pos).sub(pos);
            double distToCenter = SCRATCH_REL.length();
            double b = SCRATCH_REL.dot(SCRATCH_FWD);
            if (b > 0) {
                double pickRadius = Math.max(pt.size * 2.4, distToCenter * 0.024);
                if (SCRATCH_REL.lengthSquared() - b * b <= pickRadius * pickRadius) {
                    pt.life = -1.0;
                    removedCount++;
                    continue;
                }
            }
            if (pWrite != i) SpaceEngine.particles.set(pWrite, pt);
            pWrite++;
        }
        if (pWrite < SpaceEngine.particles.size()) {
            SpaceEngine.particles.subList(pWrite, SpaceEngine.particles.size()).clear();
        }
        return removedCount;
    }

    public static void processMouseInput(long window, boolean allowRotation) {
        if (allowRotation) {
            if (!isRotating) {
                isRotating = true;
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                firstMouse = true;
            }
        } else {
            if (isRotating) {
                isRotating = false;
                firstMouse = true;
            }
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }

        if (isRotating) {
            GLFW.glfwGetCursorPos(window, SCRATCH_MX, SCRATCH_MY);
            if (firstMouse) {
                lastMouseX = SCRATCH_MX[0];
                lastMouseY = SCRATCH_MY[0];
                firstMouse = false;
            }
            rot.y -= (float) (SCRATCH_MX[0] - lastMouseX) * 0.15f;
            rot.x = Math.max(-89.0f, Math.min(89.0f, rot.x + (float) (SCRATCH_MY[0] - lastMouseY) * 0.15f));
            lastMouseX = SCRATCH_MX[0];
            lastMouseY = SCRATCH_MY[0];
        }
    }

    public static void processKeyboardInput(long window) {
        if (isLocked) return;

        double currentTime = GLFW.glfwGetTime();
        double dt = currentTime - lastKeyTime;
        if (dt > 0.1) dt = 0.016;
        lastKeyTime = currentTime;

        double pitch = Math.toRadians(rot.x), yaw = Math.toRadians(rot.y);
        SCRATCH_FWD.set(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)).normalize();
        SCRATCH_RIGHT.set(SCRATCH_FWD).cross(0, 1, 0).normalize();

        double moveSpeed = SpaceEngine.playerFlightSpeed * 50.0 * dt;

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) pos.fma(moveSpeed, SCRATCH_FWD);
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) pos.fma(-moveSpeed, SCRATCH_FWD);
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) pos.fma(moveSpeed, SCRATCH_RIGHT);
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) pos.fma(-moveSpeed, SCRATCH_RIGHT);

        if (pos.y < 1.0) pos.y = 1.0;

        resolvePlanetCollisions();
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    public static float computeLensDepthWeight(double pointViewZ, double bhViewZ, double bhR) {
        if (pointViewZ <= bhViewZ) return 0.0f;
        if (Double.isInfinite(pointViewZ)) return 1.0f;
        double dz = pointViewZ - bhViewZ;
        double span = bhViewZ * 0.45 + bhR * 3.5;
        float s = smoothstep(0.0f, 1.0f, (float) (dz / Math.max(1e-4, span)));
        return (float) (dz / Math.max(1e-4, pointViewZ)) * s;
    }

    public static void applyLensWarpToUv(float[] uv, Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up) {
        applyLensWarpToUv(uv, Double.POSITIVE_INFINITY, camPos, forward, right, up);
    }

    public static void applyLensWarpToUv(float[] uv, double pointViewZ, Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up) {
        if (SpaceEngine.shaderMode >= 2) return;
        for (int i = 0, sz = SpaceEngine.planets.size(); i < sz; i++) {
            SpaceEngine.Planet p = SpaceEngine.planets.get(i);
            if (p == null || p.mass <= 0.0 || !"Hole".equals(p.type)) continue;

            double r = Math.max(p.radius, 0.05);
            double rx = p.pos.x - camPos.x, ry = p.pos.y - camPos.y, rz = p.pos.z - camPos.z;
            double bhViewZ = rx * forward.x + ry * forward.y + rz * forward.z;
            float weight = computeLensDepthWeight(pointViewZ, bhViewZ, r);
            if (weight <= 1e-5f) continue;

            double tx = rx / r, ty = ry / r, tz = rz / r;
            double distSqN = tx * tx + ty * ty + tz * tz;
            if (distSqN <= 1e-4) continue;

            double invDistN = 1.0 / Math.sqrt(distSqN);
            double viewZN = bhViewZ / r;
            if (viewZN * invDistN < 0.15 || viewZN <= 1e-4) continue;

            float ctrX = (float) ((tx * right.x + ty * right.y + tz * right.z) / viewZN);
            float ctrY = (float) (-(tx * up.x + ty * up.y + tz * up.z) / viewZN);
            float rs = (float) Math.min((1.0 / viewZN) * 1.35, 3.2);
            if (rs <= 0.0f) continue;

            float px = uv[0] - ctrX, py = uv[1] - ctrY;
            float d = (float) Math.sqrt(px * px + py * py);
            if (d > 1e-5f) {
                float fade = 1.0f - smoothstep(rs * 0.55f, rs * 36.0f, d);
                fade *= fade;
                if (fade > 0.0f) {
                    float pull = ((rs * rs * 2.8f * weight) / Math.max(d, rs * 0.18f)) * fade;
                    uv[0] -= (px / d) * pull;
                    uv[1] -= (py / d) * pull;
                }
            }
        }
    }

    public static void invertLensWarpUv(float[] uv, Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up) {
        invertLensWarpUv(uv, Double.POSITIVE_INFINITY, camPos, forward, right, up);
    }

    public static void invertLensWarpUv(float[] uv, double pointViewZ, Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up) {
        if (SpaceEngine.shaderMode >= 2) return;
        for (int i = SpaceEngine.planets.size() - 1; i >= 0; i--) {
            SpaceEngine.Planet p = SpaceEngine.planets.get(i);
            if (p == null || p.mass <= 0.0 || !"Hole".equals(p.type)) continue;

            double r = Math.max(p.radius, 0.05);
            double rx = p.pos.x - camPos.x, ry = p.pos.y - camPos.y, rz = p.pos.z - camPos.z;
            double bhViewZ = rx * forward.x + ry * forward.y + rz * forward.z;
            float weight = computeLensDepthWeight(pointViewZ, bhViewZ, r);
            if (weight <= 1e-5f) continue;

            double tx = rx / r, ty = ry / r, tz = rz / r;
            double distSqN = tx * tx + ty * ty + tz * tz;
            if (distSqN <= 1e-4) continue;

            double invDistN = 1.0 / Math.sqrt(distSqN);
            double viewZN = bhViewZ / r;
            if (viewZN * invDistN < 0.15 || viewZN <= 1e-4) continue;

            float ctrX = (float) ((tx * right.x + ty * right.y + tz * right.z) / viewZN);
            float ctrY = (float) (-(tx * up.x + ty * up.y + tz * up.z) / viewZN);
            float rs = (float) Math.min((1.0 / viewZN) * 1.35, 3.2);
            if (rs <= 0.0f) continue;

            float baseK = rs * rs * 2.8f * weight;
            float px = uv[0] - ctrX, py = uv[1] - ctrY;
            float dWarped = (float) Math.sqrt(px * px + py * py);
            if (dWarped > 1e-5f) {
                float screenD = 0.5f * (dWarped + (float) Math.sqrt(dWarped * dWarped + 4.0f * baseK));
                for (int iter = 0; iter < 8; iter++) {
                    float fade = 1.0f - smoothstep(rs * 0.55f, rs * 36.0f, screenD);
                    fade *= fade;
                    float k = baseK * Math.max(0.0f, fade);
                    float denom = Math.max(screenD, rs * 0.18f);
                    float fVal = (screenD - k / denom) - dWarped;
                    float df = (screenD > rs * 0.18f) ? (1.0f + k / (screenD * screenD)) : 1.0f;
                    screenD = Math.max(1e-5f, screenD - fVal / df);
                }
                uv[0] = ctrX + (px / dWarped) * screenD;
                uv[1] = ctrY + (py / dWarped) * screenD;
            } else {
                uv[0] = ctrX + (float) Math.sqrt(baseK);
                uv[1] = ctrY;
            }
        }
    }

    public static Vector3f getRawMouseRay() {
        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        if (window != 0) {
            GLFW.glfwGetWindowSize(window, SCRATCH_W, SCRATCH_H);
            GLFW.glfwGetCursorPos(window, SCRATCH_MX, SCRATCH_MY);
        }
        if (SCRATCH_W[0] <= 0 || SCRATCH_H[0] <= 0) { SCRATCH_W[0] = 1920; SCRATCH_H[0] = 1080; }

        float uvX = (float) ((SCRATCH_MX[0] - 0.5 * SCRATCH_W[0]) / SCRATCH_H[0]);
        float uvY = (float) ((SCRATCH_MY[0] - 0.5 * SCRATCH_H[0]) / SCRATCH_H[0]);

        double pitchD = Math.toRadians(rot.x), yawD = Math.toRadians(rot.y);
        SCRATCH_RAW_FWD.set(-Math.sin(yawD) * Math.cos(pitchD), -Math.sin(pitchD), -Math.cos(yawD) * Math.cos(pitchD)).normalize();
        SCRATCH_RIGHT.set(SCRATCH_RAW_FWD).cross(0, 1, 0).normalize();
        SCRATCH_PUSH.set(SCRATCH_RIGHT).cross(SCRATCH_RAW_FWD).normalize();

        SCRATCH_RAW_RAY_OUT.set(
                (float) (SCRATCH_RAW_FWD.x + uvX * SCRATCH_RIGHT.x - uvY * SCRATCH_PUSH.x),
                (float) (SCRATCH_RAW_FWD.y + uvX * SCRATCH_RIGHT.y - uvY * SCRATCH_PUSH.y),
                (float) (SCRATCH_RAW_FWD.z + uvX * SCRATCH_RIGHT.z - uvY * SCRATCH_PUSH.z)
        ).normalize();
        return SCRATCH_RAW_RAY_OUT;
    }

    public static Vector3f getMouseRay() {
        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        if (window != 0) {
            GLFW.glfwGetWindowSize(window, SCRATCH_W, SCRATCH_H);
            GLFW.glfwGetCursorPos(window, SCRATCH_MX, SCRATCH_MY);
        }
        if (SCRATCH_W[0] <= 0 || SCRATCH_H[0] <= 0) { SCRATCH_W[0] = 1920; SCRATCH_H[0] = 1080; }

        float uv0 = (float) ((SCRATCH_MX[0] - 0.5 * SCRATCH_W[0]) / SCRATCH_H[0]);
        float uv1 = (float) ((SCRATCH_MY[0] - 0.5 * SCRATCH_H[0]) / SCRATCH_H[0]);

        double pitchD = Math.toRadians(rot.x), yawD = Math.toRadians(rot.y);
        SCRATCH_FWD.set(-Math.sin(yawD) * Math.cos(pitchD), -Math.sin(pitchD), -Math.cos(yawD) * Math.cos(pitchD)).normalize();
        SCRATCH_RIGHT.set(SCRATCH_FWD).cross(0, 1, 0).normalize();
        SCRATCH_PUSH.set(SCRATCH_RIGHT).cross(SCRATCH_FWD).normalize();

        double minSafeDist = 10.0;
        if (SpaceEngine.isPlacingPlanet && SpaceEngine.menuItems != null && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length) {
            minSafeDist = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex].radius * 4.2 + 5.0;
        }

        float wx = uv0, wy = uv1;
        for (int iter = 0; iter < 4; iter++) {
            double dirX = SCRATCH_FWD.x + wx * SCRATCH_RIGHT.x - wy * SCRATCH_PUSH.x;
            double dirY = SCRATCH_FWD.y + wx * SCRATCH_RIGHT.y - wy * SCRATCH_PUSH.y;
            double dirZ = SCRATCH_FWD.z + wx * SCRATCH_RIGHT.z - wy * SCRATCH_PUSH.z;
            double invLen = 1.0 / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            dirX *= invLen; dirY *= invLen; dirZ *= invLen;

            double tPlane = (dirY < -0.001) ? (-pos.y / dirY) : minSafeDist;
            if (tPlane < minSafeDist) tPlane = minSafeDist;
            double fwdDot = dirX * SCRATCH_FWD.x + dirY * SCRATCH_FWD.y + dirZ * SCRATCH_FWD.z;
            double estViewZ = Math.max(0.1, tPlane * fwdDot);

            SCRATCH_UV[0] = uv0;
            SCRATCH_UV[1] = uv1;
            applyLensWarpToUv(SCRATCH_UV, estViewZ, pos, SCRATCH_FWD, SCRATCH_RIGHT, SCRATCH_PUSH);
            wx = SCRATCH_UV[0];
            wy = SCRATCH_UV[1];
        }

        SCRATCH_RAY_OUT.set(
                (float) (SCRATCH_FWD.x + wx * SCRATCH_RIGHT.x - wy * SCRATCH_PUSH.x),
                (float) (SCRATCH_FWD.y + wx * SCRATCH_RIGHT.y - wy * SCRATCH_PUSH.y),
                (float) (SCRATCH_FWD.z + wx * SCRATCH_RIGHT.z - wy * SCRATCH_PUSH.z)
        ).normalize();
        return SCRATCH_RAY_OUT;
    }
}