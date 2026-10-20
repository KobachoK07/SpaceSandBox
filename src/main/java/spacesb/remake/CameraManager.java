package spacesb.remake;

import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector4f;
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

    public static boolean isRotating() {
        return isRotating;
    }

    public static void update(SpaceEngine.Planet currentHologram) {
        double currentTime = GLFW.glfwGetTime();
        double dt = currentTime - lastTime;
        lastTime = currentTime;

        // Если спавним планету, но не залочены
        if (currentHologram != null && !isLocked && SpaceEngine.currentSpawnMode != SpaceEngine.SpawnMode.LAUNCH) {
            double requiredDist = currentHologram.radius * 6.0;
            double currentDist = pos.distance(currentHologram.pos);

            if (currentDist < requiredDist) {
                Vector3d pushDir = new Vector3d(pos).sub(currentHologram.pos);
                if (pushDir.lengthSquared() > 0.001) {
                    pushDir.normalize();
                } else {
                    pushDir.set(0, 1, 0);
                }
                Vector3d targetPos = new Vector3d(currentHologram.pos).add(pushDir.mul(requiredDist));

                pos.set(targetPos);
            }
        }

        if (isLocked && lockedTarget != null) {
            if (!SpaceEngine.planets.contains(lockedTarget)) {
                isLocked = false;
                lockedTarget = null;
            } else {
                if (lockedTarget != SpaceEngine.prevLockedTarget || SpaceEngine.targetOrbitDistance < 0) {
                    SpaceEngine.lockedOrbitDistance = pos.distance(lockedTarget.pos);
                    SpaceEngine.targetOrbitDistance = lockedTarget.radius * 4.0;
                    SpaceEngine.prevLockedTarget = lockedTarget;
                }

                SpaceEngine.lockedOrbitDistance += (SpaceEngine.targetOrbitDistance - SpaceEngine.lockedOrbitDistance) * 15.0 * dt;

                double pitch = Math.toRadians(rot.x);
                double yaw = Math.toRadians(rot.y);

                Vector3d forward = new Vector3d(
                        Math.sin(yaw) * Math.cos(pitch),
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
            double b = oc.dot(rayDir);
            double c = oc.dot(oc) - p.radius * p.radius;
            double det = b * b - c;
            if (b > 0 && det > 0) {
                double dist = b - Math.sqrt(det);
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

            rot.y += (float) (xpos[0] - lastMouseX) * 0.15f;
            rot.x += (float) (ypos[0] - lastMouseY) * 0.15f;

            if (rot.x > 89.0f) rot.x = 89.0f;
            if (rot.x < -89.0f) rot.x = -89.0f;

            lastMouseX = xpos[0];
            lastMouseY = ypos[0];
        }
    }

    public static void processKeyboardInput(long window) {
        if (isLocked) return;

        double pitch = Math.toRadians(rot.x);
        double yaw = Math.toRadians(rot.y);

        Vector3d forward = new Vector3d(
                Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                -Math.cos(yaw) * Math.cos(pitch)
        ).normalize();

        Vector3d right = new Vector3d(forward).cross(new Vector3d(0, 1, 0)).normalize();

        double moveSpeed = SpaceEngine.playerFlightSpeed / 10.0;

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) pos.add(new Vector3d(forward).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) pos.sub(new Vector3d(forward).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) pos.add(new Vector3d(right).mul(moveSpeed));
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) pos.sub(new Vector3d(right).mul(moveSpeed));

        if (pos.y < 1.0) pos.y = 1.0;
    }

    public static Vector3f getMouseRay() {
        MinecraftClient client = MinecraftClient.getInstance();
        float aspect = (float) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
        Matrix4f proj = new Matrix4f().setPerspective((float) Math.toRadians(70), aspect, 0.1f, 1000000f);
        Matrix4f view = new Matrix4f().rotateX((float) Math.toRadians(rot.x)).rotateY((float) Math.toRadians(rot.y));

        double mx = client.mouse.getX();
        double my = client.mouse.getY();
        double winW = client.getWindow().getWidth();
        double winH = client.getWindow().getHeight();

        float ndcX = (float) ((mx / winW) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (my / winH) * 2.0);

        Vector4f rayClip = new Vector4f(ndcX, ndcY, -1.0f, 1.0f);
        Vector4f rayEye = new Matrix4f(proj).invert().transform(rayClip);
        rayEye.z = -1.0f;
        rayEye.w = 0.0f;

        Vector4f rayWorld = new Matrix4f(view).invert().transform(rayEye);
        return new Vector3f(rayWorld.x, rayWorld.y, rayWorld.z).normalize();
    }
}