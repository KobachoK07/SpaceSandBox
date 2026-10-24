package spacesb.remake;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;

import static spacesb.remake.SpaceEngine.*;

public class SpaceInput {

    public static void onScroll(double yoffset) { }

    public static void processInput(long window, int sWidth, int sHeight, float uiScale) {
        int[] keysToCheck = {GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_KP_0, GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3, GLFW.GLFW_KEY_KP_4, GLFW.GLFW_KEY_KP_5, GLFW.GLFW_KEY_KP_6, GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_KP_DECIMAL, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT};

        if (isTypingSpeed || isTypingPlanetEdit) {
            for (int k : keysToCheck) {
                boolean pressed = GLFW.glfwGetKey(window, k) == GLFW.GLFW_PRESS;
                justPressed[k] = pressed && !keyStates[k];
                keyStates[k] = pressed;
            }
            String currentBuffer = isTypingSpeed ? typedSpeed : planetEditBuffer;
            for (int i = 0; i <= 9; i++) {
                if (justPressed[GLFW.GLFW_KEY_0 + i] || justPressed[GLFW.GLFW_KEY_KP_0 + i])
                    if (currentBuffer.length() < 15) currentBuffer += i;
            }
            if (justPressed[GLFW.GLFW_KEY_PERIOD] || justPressed[GLFW.GLFW_KEY_KP_DECIMAL])
                if (!currentBuffer.contains(".") && currentBuffer.length() < 15) currentBuffer += ".";
            if (justPressed[GLFW.GLFW_KEY_MINUS] || justPressed[GLFW.GLFW_KEY_KP_SUBTRACT]) {
                if (currentBuffer.isEmpty() || !currentBuffer.startsWith("-")) currentBuffer = "-" + currentBuffer;
                else currentBuffer = currentBuffer.substring(1);
            }
            if (justPressed[GLFW.GLFW_KEY_BACKSPACE] && !currentBuffer.isEmpty())
                currentBuffer = currentBuffer.substring(0, currentBuffer.length() - 1);

            if (isTypingSpeed) typedSpeed = currentBuffer;
            else planetEditBuffer = currentBuffer;

            if (justPressed[GLFW.GLFW_KEY_ENTER] || justPressed[GLFW.GLFW_KEY_KP_ENTER] || justPressed[GLFW.GLFW_KEY_ESCAPE]) {
                if (isTypingSpeed) applyTypedSpeed();
                else applyPlanetEdit();
            }

            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
                if (!isLMBPressed) {
                    isLMBPressed = true;
                    double mouseX, mouseY;
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        DoubleBuffer mx = stack.mallocDouble(1);
                        DoubleBuffer my = stack.mallocDouble(1);
                        GLFW.glfwGetCursorPos(window, mx, my);
                        mouseX = mx.get(0) / uiScale;
                        mouseY = my.get(0) / uiScale;
                    }

                    if (isTypingSpeed && !(mouseX >= speedBoxHitX && mouseX <= speedBoxHitX + speedBoxHitW && mouseY >= speedBoxHitY && mouseY <= speedBoxHitY + speedBoxHitH))
                        applyTypedSpeed();
                    if (isTypingPlanetEdit) {
                        boolean hitBox = false;
                        for (int i = 0; i < 6; i++) {
                            if (mouseX >= editBoxHits[i][0] && mouseX <= editBoxHits[i][0] + editBoxHits[i][2] && mouseY >= editBoxHits[i][1] && mouseY <= editBoxHits[i][1] + editBoxHits[i][3]) {
                                hitBox = true;
                                break;
                            }
                        }
                        if (!hitBox) applyPlanetEdit();
                    }
                }
            } else isLMBPressed = false;
            return;
        } else {
            for (int k : keysToCheck) keyStates[k] = GLFW.glfwGetKey(window, k) == GLFW.GLFW_PRESS;
        }

        if (planets.isEmpty() && currentSpawnMode != SpawnMode.STATIC) currentSpawnMode = SpawnMode.STATIC;

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) {
            if (!spacePressed) {
                isPaused = !isPaused;
                spacePressed = true;
            }
        } else spacePressed = false;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            if (!upPressed) {
                timeScale *= 2.0;
                upPressed = true;
            }
        } else upPressed = false;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            if (!downPressed) {
                timeScale /= 2.0;
                if (timeScale < 0.001) timeScale = 0.001;
                downPressed = true;
            }
        } else downPressed = false;

        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
            if (!isLMBPressed) {
                isLMBPressed = true;
                double mouseX, mouseY;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer mx = stack.mallocDouble(1);
                    DoubleBuffer my = stack.mallocDouble(1);
                    GLFW.glfwGetCursorPos(window, mx, my);
                    mouseX = mx.get(0) / uiScale;
                    mouseY = my.get(0) / uiScale;
                }

                if (isWelcomeMenuOpen) {
                    int btnS = 24;
                    try {
                        if (mouseX >= modBtnX && mouseX <= modBtnX + btnS && mouseY >= modBtnY && mouseY <= modBtnY + btnS)
                            net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI("https://modrinth.com/user/KobachoK07"));
                        else if (mouseX >= tgBtnX && mouseX <= tgBtnX + btnS && mouseY >= tgBtnY && mouseY <= tgBtnY + btnS)
                            net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI("https://t.me/+ccll7a2wcshhMGYy"));
                        else if (mouseX >= daBtnX && mouseX <= daBtnX + btnS && mouseY >= daBtnY && mouseY <= daBtnY + btnS)
                            net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI("https://www.donationalerts.com/r/kobachok07"));
                        else if (mouseX >= closeBtnX && mouseX <= closeBtnX + closeBtnW && mouseY >= closeBtnY && mouseY <= closeBtnY + closeBtnH)
                            isWelcomeMenuOpen = false;
                    } catch (Exception ignored) {
                    }
                    return;
                }

                boolean uiClicked = false;

                if (CameraManager.isLocked && CameraManager.lockedTarget != null && !isWelcomeMenuOpen) {
                    if (mouseX >= minBtnX && mouseX <= minBtnX + minBtnW && mouseY >= minBtnY && mouseY <= minBtnY + minBtnH) {
                        isEditMenuMinimized = !isEditMenuMinimized;
                        uiClicked = true;
                    } else if (mouseX >= flashBtnX && mouseX <= flashBtnX + flashBtnS && mouseY >= flashBtnY && mouseY <= flashBtnY + flashBtnS) {
                        // Переключаем фонарик только у текущей планеты в фокусе
                        CameraManager.lockedTarget.flashlightOn = !CameraManager.lockedTarget.flashlightOn;
                        SpaceUI.addNotification("Flashlight (" + CameraManager.lockedTarget.name + "): " + (CameraManager.lockedTarget.flashlightOn ? "ON" : "OFF"));
                        uiClicked = true;
                    } else if (mouseX >= raysBtnX && mouseX <= raysBtnX + raysBtnS && mouseY >= raysBtnY && mouseY <= raysBtnY + raysBtnS) {
                        // Переключаем лучи только у текущей звезды в фокусе
                        CameraManager.lockedTarget.raysOn = !CameraManager.lockedTarget.raysOn;
                        SpaceUI.addNotification("Star Rays (" + CameraManager.lockedTarget.name + "): " + (CameraManager.lockedTarget.raysOn ? "ON" : "OFF"));
                        uiClicked = true;
                    } else if (mouseX >= currentEditMenuX && mouseX <= currentEditMenuX + currentEditMenuW && mouseY >= 0 && mouseY <= sHeight - 26) {
                        uiClicked = true;
                        if (!isEditMenuMinimized) {
                            for (int i = 0; i < 6; i++) {
                                boolean multHit = false;
                                if (i < 3) {
                                    for (int j = 0; j < 5; j++) {
                                        if (mouseX >= multBoxHits[i][j][0] && mouseX <= multBoxHits[i][j][0] + multBoxHits[i][j][2] && mouseY >= multBoxHits[i][j][1] && mouseY <= multBoxHits[i][j][1] + multBoxHits[i][j][3]) {
                                            double[] mults = {0.1, 0.5, 2.0, 5.0, 10.0};
                                            double m = mults[j];
                                            Planet p = CameraManager.lockedTarget;
                                            if (i == 0) p.mass *= m;
                                            else if (i == 1) {
                                                p.radius *= m;
                                                p.targetRadius = p.radius;
                                            } else if (i == 2) p.rotationPeriod *= m;
                                            multHit = true;
                                            break;
                                        }
                                    }
                                }
                                if (multHit) break;
                                if (mouseX >= editBoxHits[i][0] && mouseX <= editBoxHits[i][0] + editBoxHits[i][2] && mouseY >= editBoxHits[i][1] && mouseY <= editBoxHits[i][1] + editBoxHits[i][3]) {
                                    activeEditField = i;
                                    isTypingPlanetEdit = true;
                                    planetEditBuffer = "";
                                    break;
                                }
                                if (mouseX >= unitBoxHits[i][0] && mouseX <= unitBoxHits[i][0] + unitBoxHits[i][2] && mouseY >= unitBoxHits[i][1] && mouseY <= unitBoxHits[i][1] + unitBoxHits[i][3]) {
                                    int[] maxU = {3, 3, 3, 1, 4, 3};
                                    fieldUnits[i] = (fieldUnits[i] + 1) % maxU[i];
                                    break;
                                }
                            }
                        }
                    }
                }

                if (!uiClicked && mouseY >= sHeight - 26) {
                    if (mouseX >= playHitX && mouseX <= playHitX + playHitW && mouseY >= playHitY && mouseY <= playHitY + playHitH) {
                        isPaused = !isPaused;
                        uiClicked = true;
                    } else if (mouseX >= leftArrowHitX && mouseX <= leftArrowHitX + leftArrowHitW && mouseY >= leftArrowHitY && mouseY <= leftArrowHitY + leftArrowHitH) {
                        timeScale /= 2.0;
                        if (timeScale < 0.001) timeScale = 0.001;
                        uiClicked = true;
                    } else if (mouseX >= rightArrowHitX && mouseX <= rightArrowHitX + rightArrowHitW && mouseY >= rightArrowHitY && mouseY <= rightArrowHitY + rightArrowHitH) {
                        timeScale *= 2.0;
                        uiClicked = true;
                    }
                }

                if (!uiClicked && mouseX >= toolsBtnX && mouseX <= toolsBtnX + toolsBtnW && mouseY >= toolsBtnY && mouseY <= toolsBtnY + toolsBtnH) {
                    isToolsMenuOpen = !isToolsMenuOpen;
                    if (isToolsMenuOpen) isAddMenuOpen = false;
                    uiClicked = true;
                } else if (!uiClicked && mouseX >= addBtnX && mouseX <= addBtnX + addBtnW && mouseY >= addBtnY && mouseY <= addBtnY + addBtnH) {
                    isAddMenuOpen = !isAddMenuOpen;
                    if (isAddMenuOpen) isToolsMenuOpen = false;
                    uiClicked = true;
                }

                if (!uiClicked && isToolsMenuOpen) {
                    if (mouseX >= toolMenuX && mouseX <= toolMenuX + toolMenuW && mouseY >= toolMenuY && mouseY <= toolMenuY + toolMenuH) {
                        uiClicked = true;
                        int btnW = toolMenuW - 16, btnX = toolMenuX + 8, toggleH = 20;
                        if (mouseX >= btnX && mouseX <= btnX + btnW) {
                            if (mouseY >= physicsToolY && mouseY <= physicsToolY + toggleH)
                                physicsEnabled = !physicsEnabled;
                            else if (mouseY >= moveToolY && mouseY <= moveToolY + toggleH)
                                isMoveToolActive = !isMoveToolActive;
                            else if (mouseY >= gridToolY && mouseY <= gridToolY + toggleH) showGrid = !showGrid;
                            else if (mouseY >= trailToolY && mouseY <= trailToolY + toggleH) showTrails = !showTrails;
                            else if (mouseY >= clearToolY && mouseY <= clearToolY + toggleH) {
                                planets.clear();
                                particles.clear();
                                orbitTarget = null;
                                CameraManager.isLocked = false;
                                CameraManager.lockedTarget = null;
                                currentSpawnMode = SpawnMode.STATIC;
                            }
                        }
                    } else if (mouseY < toolMenuY) isToolsMenuOpen = false;
                }

                if (!uiClicked && isAddMenuOpen) {
                    boolean planetClicked = false;
                    int menuHeight = 135, rectY = sHeight - 26, menuY = rectY - menuHeight, sepX = sWidth - 185;

                    if (mouseY >= catHitY && mouseY <= catHitY + catHitH && mouseX < sepX) {
                        for (int c = 0; c < 5; c++) {
                            if (mouseX >= catHitX[c] && mouseX <= catHitX[c] + catHitW[c]) {
                                currentCategory = c;
                                menuScrollY = 0;
                                uiClicked = true;
                                break;
                            }
                        }
                    }

                    if (!uiClicked && mouseX < sepX && mouseY >= menuY + 30 && mouseY <= rectY) {
                        for (int i = 0; i < menuItems.length; i++) {
                            if (planetHitboxes[i][0] != -1 && mouseX >= planetHitboxes[i][0] && mouseX <= planetHitboxes[i][0] + planetHitboxes[i][2] && mouseY >= planetHitboxes[i][1] && mouseY <= planetHitboxes[i][1] + planetHitboxes[i][3]) {
                                selectedPlanetIndex = i;
                                isPlacingPlanet = true;
                                isAddMenuOpen = false;
                                isCustomSpeed = false;
                                uiClicked = true;
                                planetClicked = true;

                                if (currentSpawnMode == SpawnMode.ORBIT && (orbitTarget == null || !planets.contains(orbitTarget))) {
                                    if (!planets.isEmpty()) {
                                        orbitTarget = planets.get(0);
                                        for (Planet p : planets) if (p.mass > orbitTarget.mass) orbitTarget = p;
                                    } else {
                                        currentSpawnMode = SpawnMode.STATIC;
                                    }
                                }
                                break;
                            }
                        }
                    }

                    if (!planetClicked) {
                        if (mouseX >= staticBtnX && mouseX <= staticBtnX + staticBtnW && mouseY >= staticBtnY && mouseY <= staticBtnY + staticBtnH) {
                            currentSpawnMode = SpawnMode.STATIC;
                            isCustomSpeed = false;
                            uiClicked = true;
                        } else if (mouseX >= orbitBtnX && mouseX <= orbitBtnX + orbitBtnW && mouseY >= orbitBtnY && mouseY <= orbitBtnY + orbitBtnH) {
                            if (!planets.isEmpty()) {
                                currentSpawnMode = SpawnMode.ORBIT;
                                isCustomSpeed = false;
                                if (orbitTarget == null || !planets.contains(orbitTarget)) {
                                    orbitTarget = planets.get(0);
                                    for (Planet p : planets) if (p.mass > orbitTarget.mass) orbitTarget = p;
                                }
                            }
                            uiClicked = true;
                        } else if (mouseX >= launchBtnX && mouseX <= launchBtnX + launchBtnW && mouseY >= launchBtnY && mouseY <= launchBtnY + launchBtnH) {
                            if (!planets.isEmpty()) {
                                currentSpawnMode = SpawnMode.LAUNCH;
                                isCustomSpeed = false;
                            }
                            uiClicked = true;
                        } else if ((currentSpawnMode == SpawnMode.ORBIT || currentSpawnMode == SpawnMode.LAUNCH) && !planets.isEmpty()) {
                            if (currentSpawnMode == SpawnMode.ORBIT && mouseX >= autoToggleHitX && mouseX <= autoToggleHitX + autoToggleHitW && mouseY >= autoToggleHitY && mouseY <= autoToggleHitY + autoToggleHitH) {
                                autoOrbitSpeed = !autoOrbitSpeed;
                                uiClicked = true;
                            } else if (isUnitMenuOpen) {
                                if (mouseX >= unitMenuHitX && mouseX <= unitMenuHitX + unitMenuHitW && mouseY >= unitMenuHitY && mouseY <= unitMenuHitY + unitMenuHitH) {
                                    int clickedIndex = (int) (mouseY - unitMenuHitY) / 16;
                                    if (clickedIndex >= 0 && clickedIndex <= 3) changeUnit(clickedIndex);
                                    uiClicked = true;
                                }
                                isUnitMenuOpen = false;
                            } else {
                                boolean speedBlocked = (currentSpawnMode == SpawnMode.ORBIT && autoOrbitSpeed);
                                if (mouseX >= speedBoxHitX && mouseX <= speedBoxHitX + speedBoxHitW && mouseY >= speedBoxHitY && mouseY <= speedBoxHitY + speedBoxHitH) {
                                    if (!speedBlocked) {
                                        isTypingSpeed = true;
                                        typedSpeed = "";
                                        uiClicked = true;
                                    }
                                } else if (mouseX >= unitBoxHitX && mouseX <= unitBoxHitX + unitBoxHitW && mouseY >= unitBoxHitY && mouseY <= unitBoxHitY + unitBoxHitH) {
                                    if (!speedBlocked) {
                                        isUnitMenuOpen = true;
                                        uiClicked = true;
                                    }
                                }
                            }
                        }
                        if (!uiClicked && mouseY < menuY && !isUnitMenuOpen) isAddMenuOpen = false;
                    }
                }

                if (!uiClicked && isPlacingPlanet) {
                    if (currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null) {
                        isDraggingOrbit = true;
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            DoubleBuffer my = stack.mallocDouble(1);
                            GLFW.glfwGetCursorPos(window, null, my);
                            orbitDragStartY = my.get(0) / uiScale;
                        }
                        baseOrbitPos.set(getHologramPos(menuItems[selectedPlanetIndex].radius));
                        orbitInclination = 0.0;
                    } else if (currentSpawnMode == SpawnMode.STATIC || currentSpawnMode == SpawnMode.LAUNCH) {
                        if (validSpawn) {
                            spawnPlanet(getHologramPos(menuItems[selectedPlanetIndex].radius), new Vector3d(0, 1, 0));
                            isPlacingPlanet = false;
                        }
                    }
                }

                if (!uiClicked && !isPlacingPlanet) {
                    if (isMoveToolActive) {
                        Vector3f dirF = CameraManager.getMouseRay();
                        Vector3d dir = new Vector3d(dirF.x, dirF.y, dirF.z);
                        for (Planet p : planets) {
                            Vector3d oc = new Vector3d(p.pos).sub(CameraManager.pos);
                            double b = oc.dot(dir);
                            double c = oc.dot(oc) - p.radius * p.radius;
                            double det = b * b - c;
                            if (b > 0 && det > 0) {
                                draggedPlanet = p;
                                break;
                            }
                        }
                    } else CameraManager.handleLeftClick();
                }
            } else if (isDraggingOrbit) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer my = stack.mallocDouble(1);
                    GLFW.glfwGetCursorPos(window, null, my);
                    orbitInclination = (orbitDragStartY - (my.get(0) / uiScale)) * 0.005;
                }
            }
        } else {
            if (isLMBPressed && isDraggingOrbit) {
                if (validSpawn && orbitTarget != null) spawnPlanetWithOrbit(baseOrbitPos, orbitInclination);
                isDraggingOrbit = false;
                isPlacingPlanet = false;
            }
            isLMBPressed = false;
            draggedPlanet = null;
        }

        boolean isRightClick = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (isRightClick) {
            if (!isRMBPressed) {
                isRMBPressed = true;
                if (!isWelcomeMenuOpen) {
                    // Проверяем клик ПКМ по планете: выбираем её как цель для спавна на орбиту (orbitTarget)
                    Planet hit = CameraManager.getRaycastHit();
                    if (hit != null && (isPlacingPlanet || currentSpawnMode == SpawnMode.ORBIT || isAddMenuOpen)) {
                        currentSpawnMode = SpawnMode.ORBIT;
                        orbitTarget = hit;
                        isDraggingOrbit = false;
                        SpaceUI.addNotification("Orbit Target: " + hit.name);
                    } else if (isPlacingPlanet && isDraggingOrbit) {
                        isDraggingOrbit = false;
                    }
                }
            }
        } else {
            isRMBPressed = false;
        }

        // Позволяем крутить камеру зажатой ПКМ в любой момент (даже во время выбора орбиты!)
        if (!isWelcomeMenuOpen) {
            CameraManager.processMouseInput(window, isRightClick);
            CameraManager.processKeyboardInput(window);
        }
    }
}