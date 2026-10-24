package spacesb.remake;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

public class CameraManager {
    public static final Vector3d pos = new Vector3d(0, 150, 0);
    public static final Vector3f rot = new Vector3f(45, 0, 0);

    public static SpaceEngine.Planet lockedTarget = null;
    public static boolean isLocked = false;

    private static boolean isRotating = false;
    private static double lastMouseX = 0;
    private static double lastMouseY = 0;
    private static boolean firstMouse = true;

    private static double lastTime = GLFW.glfwGetTime();
    private static double lastLeftClickTime = 0;
    private static double lastKeyTime = GLFW.glfwGetTime();

    public static boolean isRotating() {
        return isRotating;
    }

    public static Matrix4f getProjectionMatrix() {
        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        int[] w = new int[1], h = new int[1];
        if (window != 0) GLFW.glfwGetFramebufferSize(window, w, h);
        float aspect = (h[0] > 0) ? (float) w[0] / (float) h[0] : (16.0f / 9.0f);
        return new Matrix4f().setPerspective((float) Math.toRadians(70), aspect, 0.1f, Float.POSITIVE_INFINITY);
    }

    public static Matrix4f getViewMatrix() {
        Matrix4f view = new Matrix4f();
        view.rotateX((float) Math.toRadians(rot.x));
        view.rotateY((float) Math.toRadians(rot.y));
        view.translate((float) -pos.x, (float) -pos.y, (float) -pos.z);
        return view;
    }

    // Автоматическое отдаление камеры при выборе огромной планеты в меню Add, которая не влезает в экран
    public static void ensureFitsOnScreen(double planetRadius) {
        double requiredHeight = planetRadius * 3.6;
        if (pos.y < requiredHeight) {
            double pitch = Math.toRadians(rot.x);
            double yaw = Math.toRadians(rot.y);
            Vector3d forward = new Vector3d(
                    -Math.sin(yaw) * Math.cos(pitch),
                    -Math.sin(pitch),
                    -Math.cos(yaw) * Math.cos(pitch)
            ).normalize();
            double pullBack = (requiredHeight - pos.y) / Math.max(0.25, Math.abs(forward.y));
            pos.sub(new Vector3d(forward).mul(pullBack));
            if (pos.y < requiredHeight) pos.y = requiredHeight;
        }
        // Автоматически подстраиваем минимальную скорость полёта, чтобы вокруг гиганта можно было комфортно летать
        double minComfortableSpeed = planetRadius * 0.5;
        if (SpaceEngine.playerFlightSpeed < minComfortableSpeed) {
            SpaceEngine.playerFlightSpeed = Math.min(1000000.0, minComfortableSpeed);
        }
    }

    public static void update(SpaceEngine.Planet currentHologram) {
        double currentTime = GLFW.glfwGetTime();
        double dt = currentTime - lastTime;
        lastTime = currentTime;

        // Если активен предпросмотр спавна (голограмма) и объект не входит в монитор — плавно отодвигаем камеру
        if (SpaceEngine.isPlacingPlanet && !isLocked && SpaceEngine.menuItems != null
                && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length
                && SpaceEngine.currentSpawnMode != SpaceEngine.SpawnMode.LAUNCH) {
            SpaceEngine.MenuItem item = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex];
            Vector3d holoPos = SpaceEngine.getHologramPos(item.radius);
            double requiredDist = item.radius * 4.2;
            double currentDist = pos.distance(holoPos);

            if (currentDist < requiredDist) {
                Vector3d pushDir = new Vector3d(pos).sub(holoPos);
                if (pushDir.lengthSquared() > 1e-6) pushDir.normalize();
                else pushDir.set(0.0, 1.0, 0.0);

                Vector3d targetPos = new Vector3d(holoPos).fma(requiredDist, pushDir);
                pos.lerp(targetPos, Math.min(1.0, dt * 12.0));
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

                double pitch = Math.toRadians(rot.x);
                double yaw = Math.toRadians(rot.y);

                Vector3d forward = new Vector3d(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                        -Math.cos(yaw) * Math.cos(pitch)
                ).normalize();

                pos.set(new Vector3d(lockedTarget.pos).sub(forward.mul(SpaceEngine.lockedOrbitDistance)));
            }
        } else {
            SpaceEngine.prevLockedTarget = null;
            SpaceEngine.targetOrbitDistance = -1.0;
            SpaceEngine.lockedOrbitDistance = -1.0;
        }

        // Запрещаем камере пролетать сквозь любые планеты в симуляции
        resolvePlanetCollisions();
    }

    // 3D-коллизия камеры с ориентированным кубом (OBB) и сферой безопасности каждой планеты
    public static void resolvePlanetCollisions() {
        for (SpaceEngine.Planet p : SpaceEngine.planets) {
            double r = p.radius;
            double margin = Math.max(0.35, r * 0.12);
            double boxExtent = r + margin;

            Vector3d rel = new Vector3d(pos).sub(p.pos);
            // Быстрая проверка по ограничивающей сфере куба (sqrt(3) * boxExtent)
            if (rel.lengthSquared() > 3.1 * boxExtent * boxExtent) continue;

            double tiltRad = Math.toRadians(p.tilt);
            double rotRad = Math.toRadians(p.currentAngle);

            // Переводим позицию камеры в локальные координаты вращающейся планеты
            Vector3d local = new Vector3d(rel).rotateZ(-tiltRad).rotateY(-rotRad);

            if (Math.abs(local.x) < boxExtent && Math.abs(local.y) < boxExtent && Math.abs(local.z) < boxExtent) {
                double dx = boxExtent - Math.abs(local.x);
                double dy = boxExtent - Math.abs(local.y);
                double dz = boxExtent - Math.abs(local.z);

                // Выталкиваем камеру наружу по ближайшей грани куба
                if (dx <= dy && dx <= dz) {
                    local.x = (local.x >= 0.0) ? boxExtent : -boxExtent;
                } else if (dy <= dx && dy <= dz) {
                    local.y = (local.y >= 0.0) ? boxExtent : -boxExtent;
                } else {
                    local.z = (local.z >= 0.0) ? boxExtent : -boxExtent;
                }

                // Возвращаем вектор обратно в мировые координаты
                local.rotateY(rotRad).rotateZ(tiltRad);
                pos.set(p.pos).add(local);
            }
        }
    }

    public static void handleLeftClick() {
        double currentTime = GLFW.glfwGetTime();
        if (currentTime - lastLeftClickTime < 0.3) {
            tryLockOnPlanet();
        } else {
            if (isLocked && getRaycastHit() == null) {
                isLocked = false;
                lockedTarget = null;
            }
        }
        lastLeftClickTime = currentTime;
    }

    public static SpaceEngine.Planet getRaycastHit() {
        Vector3f rayDirF = getMouseRay();
        Vector3d rayDir = new Vector3d(rayDirF.x, rayDirF.y, rayDirF.z);
        SpaceEngine.Planet hit = null;
        double minDist = Double.MAX_VALUE;

        for (SpaceEngine.Planet p : SpaceEngine.planets) {
            Vector3d oc = new Vector3d(p.pos).sub(pos);
            double distToCenter = oc.length();
            double b = oc.dot(rayDir);
            if (b <= 0) continue;

            // Учитываем диагональ куба (1.45 * radius) + минимальный экранный радиус захвата курсором (1.8% от дистанции)
            double pickRadius = Math.max(p.radius * 1.45, distToCenter * 0.018);
            double c = oc.dot(oc) - pickRadius * pickRadius;
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

    private static void tryLockOnPlanet() {
        SpaceEngine.Planet hit = getRaycastHit();
        if (hit != null) {
            isLocked = true;
            lockedTarget = hit;
        }
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
            double[] xpos = new double[1], ypos = new double[1];
            GLFW.glfwGetCursorPos(window, xpos, ypos);
            if (firstMouse) {
                lastMouseX = xpos[0];
                lastMouseY = ypos[0];
                firstMouse = false;
            }

            rot.y -= (float) (xpos[0] - lastMouseX) * 0.15f;
            rot.x += (float) (ypos[0] - lastMouseY) * 0.15f;

            if (rot.x > 89.0f) rot.x = 89.0f;
            if (rot.x < -89.0f) rot.x = -89.0f;

            lastMouseX = xpos[0];
            lastMouseY = ypos[0];
        }
    }

    public static void processKeyboardInput(long window) {
        if (isLocked) return;

        double currentTime = GLFW.glfwGetTime();
        double dt = currentTime - lastKeyTime;
        if (dt > 0.1) dt = 0.016;
        lastKeyTime = currentTime;

        double pitch = Math.toRadians(rot.x);
        double yaw = Math.toRadians(rot.y);

        Vector3d forward = new Vector3d(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                -Math.cos(yaw) * Math.cos(pitch)
        ).normalize();

        Vector3d right = new Vector3d(forward).cross(new Vector3d(0, 1, 0)).normalize();

        double moveSpeed = SpaceEngine.playerFlightSpeed * 50.0 * dt;

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) pos.add(new Vector3d(forward).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) pos.sub(new Vector3d(forward).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) pos.add(new Vector3d(right).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) pos.sub(new Vector3d(right).mul(moveSpeed));

        if (pos.y < 1.0) pos.y = 1.0;

        // Не даём пролететь сквозь планету при нажатии WASD
        resolvePlanetCollisions();
    }

    public static Vector3f getMouseRay() {
        long window = SpaceEngine.windowHandle != 0 ? SpaceEngine.windowHandle : GLFW.glfwGetCurrentContext();
        int[] w = new int[1], h = new int[1];
        double[] mx = new double[1], my = new double[1];
        if (window != 0) {
            GLFW.glfwGetWindowSize(window, w, h);
            GLFW.glfwGetCursorPos(window, mx, my);
        }
        if (w[0] <= 0 || h[0] <= 0) {
            w[0] = 1920;
            h[0] = 1080;
        }

        float uvX = (float) ((mx[0] - 0.5 * w[0]) / h[0]);
        float uvY = (float) ((my[0] - 0.5 * h[0]) / h[0]);

        float pitch = (float) Math.toRadians(rot.x);
        float yaw = (float) Math.toRadians(rot.y);

        Vector3f forward = new Vector3f(
                (float) (-Math.sin(yaw) * Math.cos(pitch)),
                (float) (-Math.sin(pitch)),
                (float) (-Math.cos(yaw) * Math.cos(pitch))
        ).normalize();
        Vector3f right = new Vector3f(forward).cross(0.0f, 1.0f, 0.0f).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();

        return new Vector3f(forward)
                .add(new Vector3f(right).mul(uvX))
                .sub(new Vector3f(up).mul(uvY))
                .normalize();
    }
}