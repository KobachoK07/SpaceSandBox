package spacesb.remake;

import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.awt.Desktop;
import java.net.URI;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import static spacesb.remake.SpaceEngine.*;

public class SpaceInput {

    private static long registeredScrollWindow = 0L;
    private static double lastMouseX = 0, lastMouseY = 0;
    private static int lastLogicalW = 854, lastLogicalH = 480;

    private static void openUrl(String url) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                } else {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                }
            } catch (Exception ignored) {}
        }, "SBox-URL-Opener").start();
    }

    private static void ensureScrollCallback(long window) {
        if (window == 0L || registeredScrollWindow == window) return;
        registeredScrollWindow = window;
        GLFW.glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            if (isWelcomeMenuOpen || isPhotoModeActive || yoffset == 0.0) return;

            if (isAddMenuOpen && lastMouseY >= lastLogicalH - 26 - 135 && lastMouseY < lastLogicalH - 26 && lastMouseX < lastLogicalW - 185) {
                menuScrollY = Math.max(0.0, menuScrollY - yoffset * 22.0);
                return;
            }

            if (CameraManager.isLocked && !isEditMenuMinimized
                    && lastMouseX <= currentEditMenuX + currentEditMenuW && lastMouseY < lastLogicalH - 26) {
                editMenuScrollY = Math.max(0.0, Math.min(140.0, editMenuScrollY - yoffset * 18.0));
                return;
            }

            if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
                if (targetOrbitDistance < 0) targetOrbitDistance = CameraManager.lockedTarget.radius * 4.0;
                if (yoffset > 0) targetOrbitDistance = Math.max(CameraManager.lockedTarget.radius * 1.85 + 0.5, targetOrbitDistance / 1.18);
                else if (yoffset < 0) targetOrbitDistance *= 1.18;
                return;
            }

            lastScrollTime = System.currentTimeMillis();
            if (yoffset > 0) playerFlightSpeed = Math.min(MAX_SPEED_KMS, playerFlightSpeed * 1.15);
            else if (yoffset < 0) playerFlightSpeed = Math.max(0.1, playerFlightSpeed / 1.15);
        });
    }

    private static Planet findDefaultOrbitTarget() {
        Planet best = null;
        double maxMass = -1.0;
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            if (p != null && p.mass > maxMass) {
                maxMass = p.mass;
                best = p;
            }
        }
        return best;
    }

    private static void updateFsrFromMouse(double mouseX) {
        int tx = escFsrTrackHit[0], tw = Math.max(1, escFsrTrackHit[2]);
        fsrSliderPos = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        fsrQualityIndex = Math.min(3, Math.max(0, (int) (fsrSliderPos * 4.0f)));
    }

    private static void updateShaderFromMouse(double mouseX) {
        int tx = escShaderTrackHit[0], tw = Math.max(1, escShaderTrackHit[2]);
        shaderSliderPos = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        int zone = Math.min(2, Math.max(0, (int) (shaderSliderPos * 3.0f)));
        shaderMode = (zone == 0) ? 2 : (zone == 1 ? 1 : 0);
    }

    private static void updateParticleFromMouse(double mouseX) {
        int tx = escParticleTrackHit[0], tw = Math.max(1, escParticleTrackHit[2]);
        particleSliderPos = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        particlePreset = Math.min(3, Math.max(0, (int) (particleSliderPos * 4.0f)));
    }

    private static void updateVolumeFromMouse(double mouseX) {
        int tx = escVolumeTrackHit[0], tw = Math.max(1, escVolumeTrackHit[2]);
        musicVolume = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        SBoxAudioEngine.setMusicVolume(musicVolume);
    }

    public static void processInput(long window, int sWidth, int sHeight, int scale) {
        lastLogicalW = sWidth;
        lastLogicalH = sHeight;
        ensureScrollCallback(window);

        if (isAddMenuOpen || isToolsMenuOpen) {
            isEscMenuOpen = false;
            isResolutionMenuOpen = false;
            SpaceUI.isLanguageMenuOpen = false;
        }

        for (int k = 32; k <= GLFW.GLFW_KEY_LAST; k++) {
            boolean down = GLFW.glfwGetKey(window, k) == GLFW.GLFW_PRESS;
            justPressed[k] = down && !keyStates[k];
            keyStates[k] = down;
        }

        if (isTypingSpeed || isTypingPlanetEdit) {
            String buf = isTypingSpeed ? typedSpeed : planetEditBuffer;
            if (justPressed[GLFW.GLFW_KEY_BACKSPACE] && !buf.isEmpty()) {
                buf = buf.substring(0, buf.length() - 1);
            }
            if (justPressed[GLFW.GLFW_KEY_ENTER] || justPressed[GLFW.GLFW_KEY_KP_ENTER]) {
                if (isTypingSpeed) applyTypedSpeed();
                else applyPlanetEdit();
                return;
            }
            if (justPressed[GLFW.GLFW_KEY_ESCAPE]) {
                isTypingSpeed = false;
                isTypingPlanetEdit = false;
                activeEditField = -1;
                escPressed = true;
                return;
            }
            for (int k = GLFW.GLFW_KEY_0; k <= GLFW.GLFW_KEY_9; k++) {
                if (justPressed[k]) buf += (char) ('0' + (k - GLFW.GLFW_KEY_0));
            }
            for (int k = GLFW.GLFW_KEY_KP_0; k <= GLFW.GLFW_KEY_KP_9; k++) {
                if (justPressed[k]) buf += (char) ('0' + (k - GLFW.GLFW_KEY_KP_0));
            }
            if ((justPressed[GLFW.GLFW_KEY_PERIOD] || justPressed[GLFW.GLFW_KEY_KP_DECIMAL] || justPressed[GLFW.GLFW_KEY_COMMA]) && !buf.contains(".")) {
                buf += ".";
            }
            if ((justPressed[GLFW.GLFW_KEY_MINUS] || justPressed[GLFW.GLFW_KEY_KP_SUBTRACT]) && buf.isEmpty()) {
                buf += "-";
            }
            if (justPressed[GLFW.GLFW_KEY_E] && !buf.toLowerCase().contains("e") && !buf.isEmpty()) {
                buf += "e";
            }
            if (isTypingSpeed) typedSpeed = buf;
            else planetEditBuffer = buf;
        }

        boolean escDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escDown && !escPressed) {
            if (isPhotoModeActive) {
                isPhotoModeActive = false;
                isPaused = wasPausedBeforePhoto;
            } else if (isTypingSpeed || isTypingPlanetEdit) {
                isTypingSpeed = false;
                isTypingPlanetEdit = false;
                activeEditField = -1;
            } else if (isPlacingPlanet) {
                isPlacingPlanet = false;
                isDraggingOrbit = false;
                orbitTarget = null;
            } else if (isAddMenuOpen) {
                isAddMenuOpen = false;
            } else if (isToolsMenuOpen) {
                isToolsMenuOpen = false;
            } else if (CameraManager.isLocked) {
                CameraManager.isLocked = false;
                CameraManager.lockedTarget = null;
                openEditUnitMenu = -1;
            } else if (isDeleteToolActive) {
                isDeleteToolActive = false;
            } else if (isMoveToolActive) {
                isMoveToolActive = false;
                draggedPlanet = null;
            } else if (!isWelcomeMenuOpen) {
                isEscMenuOpen = !isEscMenuOpen;
                isResolutionMenuOpen = false;
                SpaceUI.isLanguageMenuOpen = false;
            }
        }
        escPressed = escDown;

        boolean cDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
        if (cDown && !cKeyPressed && photoModeFeatureEnabled && !isWelcomeMenuOpen && !isTypingSpeed && !isTypingPlanetEdit) {
            if (!isPhotoModeActive) {
                wasPausedBeforePhoto = isPaused;
                isPhotoModeActive = true;
                isPaused = true;
            } else {
                isPhotoModeActive = false;
                isPaused = wasPausedBeforePhoto;
            }
        }
        cKeyPressed = cDown;

        if (!isTypingSpeed && !isTypingPlanetEdit && !isWelcomeMenuOpen) {
            if (!isPhotoModeActive) {
                boolean spaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
                if (spaceDown && !spacePressed) isPaused = !isPaused;
                spacePressed = spaceDown;

                boolean upDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;
                if (upDown && !upPressed) timeScale = Math.min(3153600000.0, timeScale * 2.0);
                upPressed = upDown;

                boolean downKey = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS;
                if (downKey && !downPressed) timeScale = Math.max(0.1, timeScale / 2.0);
                downPressed = downKey;
            } else if (justPressed[GLFW.GLFW_KEY_ENTER] || justPressed[GLFW.GLFW_KEY_SPACE]) {
                requestScreenshot = true;
            }
            CameraManager.processKeyboardInput(window);
        }

        double mouseX, mouseY;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer mx = stack.mallocDouble(1), my = stack.mallocDouble(1);
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            GLFW.glfwGetCursorPos(window, mx, my);
            GLFW.glfwGetWindowSize(window, ww, wh);
            mouseX = mx.get(0) * ((double) sWidth / Math.max(1, ww.get(0)));
            mouseY = my.get(0) * ((double) sHeight / Math.max(1, wh.get(0)));
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        boolean rmbDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        isRMBPressed = rmbDown;
        CameraManager.processMouseInput(window, rmbDown && !isWelcomeMenuOpen);

        boolean lmbDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (isPhotoModeActive) {
            if (lmbDown && !isLMBPressed) requestScreenshot = true;
            isLMBPressed = lmbDown;
            return;
        }

        if (lmbDown && !isLMBPressed) {
            if (isWelcomeMenuOpen) {
                if (mouseX >= closeBtnX && mouseX <= closeBtnX + closeBtnW && mouseY >= closeBtnY && mouseY <= closeBtnY + closeBtnH) {
                    isWelcomeMenuOpen = false;
                } else if (mouseX >= modBtnX && mouseX <= modBtnX + modBtnS && mouseY >= modBtnY && mouseY <= modBtnY + modBtnS) {
                    openUrl("https://modrinth.com/mod/spacesb");
                } else if (mouseX >= tgBtnX && mouseX <= tgBtnX + tgBtnS && mouseY >= tgBtnY && mouseY <= tgBtnY + tgBtnS) {
                    openUrl("https://t.me/spacesandbox");
                } else if (mouseX >= daBtnX && mouseX <= daBtnX + daBtnS && mouseY >= daBtnY && mouseY <= daBtnY + daBtnS) {
                    openUrl("https://www.donationalerts.com");
                }
                isLMBPressed = true;
                return;
            }

            if (isTypingSpeed && !(mouseX >= speedBoxHitX && mouseX <= speedBoxHitX + speedBoxHitW && mouseY >= speedBoxHitY && mouseY <= speedBoxHitY + speedBoxHitH)) {
                applyTypedSpeed();
            }
            if (isTypingPlanetEdit) {
                boolean clickedInsideActive = activeEditField >= 0
                        && mouseX >= editBoxHits[activeEditField][0] && mouseX <= editBoxHits[activeEditField][0] + editBoxHits[activeEditField][2]
                        && mouseY >= editBoxHits[activeEditField][1] && mouseY <= editBoxHits[activeEditField][1] + editBoxHits[activeEditField][3];
                if (!clickedInsideActive) applyPlanetEdit();
            }

            boolean uiConsumed = false;

            // Нижняя панель проверяется первой, чтобы нажатие на Add или Tools закрывало меню ESC!
            if (mouseY >= sHeight - 26) {
                uiConsumed = true;
                if (mouseX >= playHitX && mouseX <= playHitX + playHitW && mouseY >= playHitY && mouseY <= playHitY + playHitH) {
                    isPaused = !isPaused;
                } else if (mouseX >= leftArrowHitX && mouseX <= leftArrowHitX + leftArrowHitW && mouseY >= leftArrowHitY && mouseY <= leftArrowHitY + leftArrowHitH) {
                    timeScale = Math.max(0.1, timeScale / 2.0);
                } else if (mouseX >= rightArrowHitX && mouseX <= rightArrowHitX + rightArrowHitW && mouseY >= rightArrowHitY && mouseY <= rightArrowHitY + rightArrowHitH) {
                    timeScale = Math.min(3153600000.0, timeScale * 2.0);
                } else if (mouseX >= addBtnX && mouseX <= addBtnX + addBtnW && mouseY >= addBtnY && mouseY <= addBtnY + addBtnH) {
                    isAddMenuOpen = !isAddMenuOpen;
                    if (isAddMenuOpen) {
                        isToolsMenuOpen = false;
                        isDeleteToolActive = false;
                        isEscMenuOpen = false;
                        isResolutionMenuOpen = false;
                        SpaceUI.isLanguageMenuOpen = false;
                    }
                } else if (mouseX >= toolsBtnX && mouseX <= toolsBtnX + toolsBtnW && mouseY >= toolsBtnY && mouseY <= toolsBtnY + toolsBtnH) {
                    isToolsMenuOpen = !isToolsMenuOpen;
                    if (isToolsMenuOpen) {
                        isAddMenuOpen = false;
                        isEscMenuOpen = false;
                        isResolutionMenuOpen = false;
                        SpaceUI.isLanguageMenuOpen = false;
                    }
                }
            }

            if (!uiConsumed && showFpsOverlay && mouseX >= fpsOverlayX && mouseX <= fpsOverlayX + fpsOverlayW && mouseY >= fpsOverlayY && mouseY <= fpsOverlayY + 22) {
                isDraggingFpsOverlay = true;
                fpsDragOffsetX = mouseX - fpsOverlayX;
                fpsDragOffsetY = mouseY - fpsOverlayY;
                uiConsumed = true;
            }

            int escPanelW = Math.max(260, (int) (sWidth * 0.28f));
            if (!uiConsumed && isEscMenuOpen && !CameraManager.isLocked && mouseX <= escPanelW) {
                uiConsumed = true;
                if (SpaceUI.isLanguageMenuOpen) {
                    // Обрабатывается в SpaceUI
                } else if (isResolutionMenuOpen && mouseX >= escResDropdownHit[0] && mouseX <= escResDropdownHit[0] + escResDropdownHit[2]
                        && mouseY >= escResDropdownHit[1] && mouseY <= escResDropdownHit[1] + escResDropdownHit[3]) {
                    int idx = (int) ((mouseY - escResDropdownHit[1]) / 16);
                    if (idx >= 0 && idx < RESOLUTIONS.length) {
                        resolutionIndex = idx;
                        applyDisplaySettings(window);
                    }
                    isResolutionMenuOpen = false;
                } else {
                    isResolutionMenuOpen = false;
                    if (mouseX >= escTopBtnHits[0][0] && mouseX <= escTopBtnHits[0][0] + escTopBtnHits[0][2]
                            && mouseY >= escTopBtnHits[0][1] && mouseY <= escTopBtnHits[0][1] + escTopBtnHits[0][3]) {
                        planets.clear();
                        fragments.clear();
                        particles.clear();
                        simTimeSeconds = 0.0;
                        CameraManager.isLocked = false;
                        CameraManager.lockedTarget = null;
                        isPlacingPlanet = false;
                        isDraggingOrbit = false;
                        orbitTarget = null;
                        SpaceUI.addNotification("New Simulation Started");
                    } else if (mouseX >= escSettingHits[0][0] && mouseX <= escSettingHits[0][0] + escSettingHits[0][2]
                            && mouseY >= escSettingHits[0][1] && mouseY <= escSettingHits[0][1] + escSettingHits[0][3]) {
                        fsrEnabled = !fsrEnabled;
                    } else if (mouseX >= escFsrTrackHit[0] && mouseX <= escFsrTrackHit[0] + escFsrTrackHit[2]
                            && mouseY >= escFsrTrackHit[1] - 2 && mouseY <= escFsrTrackHit[1] + escFsrTrackHit[3] + 2) {
                        isDraggingFsrCube = true;
                        updateFsrFromMouse(mouseX);
                    } else if (mouseX >= escSettingHits[2][0] && mouseX <= escSettingHits[2][0] + escSettingHits[2][2]
                            && mouseY >= escSettingHits[2][1] && mouseY <= escSettingHits[2][1] + escSettingHits[2][3]) {
                        hdrEnabled = !hdrEnabled;
                    } else if (mouseX >= escSettingHits[4][0] && mouseX <= escSettingHits[4][0] + escSettingHits[4][2]
                            && mouseY >= escSettingHits[4][1] && mouseY <= escSettingHits[4][1] + escSettingHits[4][3]) {
                        vsyncEnabled = !vsyncEnabled;
                        needsSwapchainRecreate = true;
                    } else if (mouseX >= escSettingHits[5][0] && mouseX <= escSettingHits[5][0] + escSettingHits[5][2]
                            && mouseY >= escSettingHits[5][1] && mouseY <= escSettingHits[5][1] + escSettingHits[5][3]) {
                        isResolutionMenuOpen = !isResolutionMenuOpen;
                    } else if (mouseX >= escSettingHits[6][0] && mouseX <= escSettingHits[6][0] + escSettingHits[6][2]
                            && mouseY >= escSettingHits[6][1] && mouseY <= escSettingHits[6][1] + escSettingHits[6][3]) {
                        isFullscreen = !isFullscreen;
                        applyDisplaySettings(window);
                    } else if (mouseX >= escSettingHits[7][0] && mouseX <= escSettingHits[7][0] + escSettingHits[7][2]
                            && mouseY >= escSettingHits[7][1] && mouseY <= escSettingHits[7][1] + escSettingHits[7][3]) {
                        showFpsOverlay = !showFpsOverlay;
                    } else if (mouseX >= escSettingHits[8][0] && mouseX <= escSettingHits[8][0] + escSettingHits[8][2]
                            && mouseY >= escSettingHits[8][1] && mouseY <= escSettingHits[8][1] + escSettingHits[8][3]) {
                        photoModeFeatureEnabled = !photoModeFeatureEnabled;
                    } else if (mouseX >= escShaderTrackHit[0] && mouseX <= escShaderTrackHit[0] + escShaderTrackHit[2]
                            && mouseY >= escShaderTrackHit[1] - 2 && mouseY <= escShaderTrackHit[1] + escShaderTrackHit[3] + 2) {
                        isDraggingShaderCube = true;
                        updateShaderFromMouse(mouseX);
                    } else if (mouseX >= escParticleTrackHit[0] && mouseX <= escParticleTrackHit[0] + escParticleTrackHit[2]
                            && mouseY >= escParticleTrackHit[1] - 2 && mouseY <= escParticleTrackHit[1] + escParticleTrackHit[3] + 2) {
                        isDraggingParticleCube = true;
                        updateParticleFromMouse(mouseX);
                    } else if (mouseX >= escVolumeTrackHit[0] && mouseX <= escVolumeTrackHit[0] + escVolumeTrackHit[2]
                            && mouseY >= escVolumeTrackHit[1] - 2 && mouseY <= escVolumeTrackHit[1] + escVolumeTrackHit[3] + 2) {
                        isDraggingVolumeCube = true;
                        updateVolumeFromMouse(mouseX);
                    } else if (mouseX >= escExitBtnHit[0] && mouseX <= escExitBtnHit[0] + escExitBtnHit[2]
                            && mouseY >= escExitBtnHit[1] && mouseY <= escExitBtnHit[1] + escExitBtnHit[3]) {
                        SBoxAudioEngine.shutdown();
                        System.exit(0);
                    }
                }
            }

            if (!uiConsumed && isToolsMenuOpen && mouseX >= toolMenuX && mouseY >= toolMenuY && mouseY < sHeight - 26) {
                uiConsumed = true;
                int bx = toolMenuX + 8, bw = toolMenuW - 16, th = 20;
                if (mouseX >= bx && mouseX <= bx + bw) {
                    if (mouseY >= moveToolY && mouseY <= moveToolY + th) {
                        isMoveToolActive = !isMoveToolActive;
                        if (isMoveToolActive) { isDeleteToolActive = false; isPlacingPlanet = false; }
                        else draggedPlanet = null;
                    } else if (mouseY >= gridToolY && mouseY <= gridToolY + th) {
                        showGrid = !showGrid;
                        if (showGrid) SpaceUI.showGravityGrid = false;
                    } else if (mouseY >= SpaceUI.gravGridToolY && mouseY <= SpaceUI.gravGridToolY + th) {
                        // Переключается в SpaceUI
                    } else if (mouseY >= trailToolY && mouseY <= trailToolY + th) {
                        showTrails = !showTrails;
                    } else if (mouseY >= SpaceUI.hzToolY && mouseY <= SpaceUI.hzToolY + th) {
                        SpaceUI.showHabitableZone = !SpaceUI.showHabitableZone;
                    } else if (mouseY >= physicsToolY && mouseY <= physicsToolY + th) {
                        physicsEnabled = !physicsEnabled;
                    } else if (mouseY >= clearToolY && mouseY <= clearToolY + th) {
                        isDeleteToolActive = !isDeleteToolActive;
                        if (isDeleteToolActive) { isMoveToolActive = false; draggedPlanet = null; isPlacingPlanet = false; }
                    }
                }
            }

            if (!uiConsumed && isAddMenuOpen && mouseY >= sHeight - 26 - 135 && mouseY < sHeight - 26) {
                uiConsumed = true;
                if (isUnitMenuOpen && mouseX >= unitMenuHitX && mouseX <= unitMenuHitX + unitMenuHitW && mouseY >= unitMenuHitY && mouseY <= unitMenuHitY + unitMenuHitH) {
                    int uIdx = (int) ((mouseY - unitMenuHitY) / 16);
                    if (uIdx >= 0 && uIdx < 4) changeUnit(uIdx);
                    isUnitMenuOpen = false;
                } else {
                    isUnitMenuOpen = false;
                    if (mouseY >= catHitY && mouseY <= catHitY + catHitH) {
                        for (int c = 0; c < 5; c++) {
                            if (mouseX >= catHitX[c] && mouseX <= catHitX[c] + catHitW[c]) {
                                currentCategory = c;
                                menuScrollY = 0;
                                break;
                            }
                        }
                    } else if (mouseX >= staticBtnX && mouseX <= staticBtnX + staticBtnW && mouseY >= staticBtnY && mouseY <= staticBtnY + staticBtnH) {
                        currentSpawnMode = SpawnMode.STATIC;
                    } else if (mouseX >= orbitBtnX && mouseX <= orbitBtnX + orbitBtnW && mouseY >= orbitBtnY && mouseY <= orbitBtnY + orbitBtnH && !planets.isEmpty()) {
                        currentSpawnMode = SpawnMode.ORBIT;
                        if (orbitTarget == null || !planets.contains(orbitTarget)) orbitTarget = findDefaultOrbitTarget();
                    } else if (mouseX >= launchBtnX && mouseX <= launchBtnX + launchBtnW && mouseY >= launchBtnY && mouseY <= launchBtnY + launchBtnH && !planets.isEmpty()) {
                        currentSpawnMode = SpawnMode.LAUNCH;
                    } else if ((currentSpawnMode == SpawnMode.LAUNCH || currentSpawnMode == SpawnMode.ORBIT) && !planets.isEmpty()
                            && mouseX >= speedBoxHitX && mouseX <= speedBoxHitX + speedBoxHitW && mouseY >= speedBoxHitY && mouseY <= speedBoxHitY + speedBoxHitH) {
                        if (!(currentSpawnMode == SpawnMode.ORBIT && autoOrbitSpeed)) {
                            isTypingSpeed = true;
                            typedSpeed = "";
                        }
                    } else if ((currentSpawnMode == SpawnMode.LAUNCH || currentSpawnMode == SpawnMode.ORBIT) && !planets.isEmpty()
                            && mouseX >= unitBoxHitX && mouseX <= unitBoxHitX + unitBoxHitW && mouseY >= unitBoxHitY && mouseY <= unitBoxHitY + unitBoxHitH) {
                        if (!(currentSpawnMode == SpawnMode.ORBIT && autoOrbitSpeed)) {
                            isUnitMenuOpen = !isUnitMenuOpen;
                        }
                    } else if (currentSpawnMode == SpawnMode.ORBIT && !planets.isEmpty()
                            && mouseX >= autoToggleHitX && mouseX <= autoToggleHitX + autoToggleHitW + 40 && mouseY >= autoToggleHitY - 2 && mouseY <= autoToggleHitY + autoToggleHitH + 2) {
                        autoOrbitSpeed = !autoOrbitSpeed;
                    } else {
                        for (int i = 0; i < menuItems.length && i < planetHitboxes.length; i++) {
                            if (planetHitboxes[i][0] != -1
                                    && mouseX >= planetHitboxes[i][0] && mouseX <= planetHitboxes[i][0] + planetHitboxes[i][2]
                                    && mouseY >= planetHitboxes[i][1] && mouseY <= planetHitboxes[i][1] + planetHitboxes[i][3]) {
                                selectedPlanetIndex = i;
                                isPlacingPlanet = true;
                                isAddMenuOpen = false;
                                isToolsMenuOpen = false;
                                isEscMenuOpen = false;
                                isDeleteToolActive = false;
                                isMoveToolActive = false;
                                if (currentSpawnMode == SpawnMode.ORBIT) {
                                    if (orbitTarget == null || !planets.contains(orbitTarget)) {
                                        orbitTarget = findDefaultOrbitTarget();
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }

            if (!uiConsumed && CameraManager.isLocked && CameraManager.lockedTarget != null) {
                Planet p = CameraManager.lockedTarget;
                if (mouseX >= minBtnX && mouseX <= minBtnX + minBtnW && mouseY >= minBtnY && mouseY <= minBtnY + minBtnH) {
                    isEditMenuMinimized = !isEditMenuMinimized;
                    openEditUnitMenu = -1;
                    uiConsumed = true;
                } else if (mouseX >= flashBtnX && mouseX <= flashBtnX + flashBtnS && mouseY >= flashBtnY && mouseY <= flashBtnY + flashBtnS) {
                    p.flashlightOn = !p.flashlightOn;
                    uiConsumed = true;
                } else if (mouseX >= raysBtnX && mouseX <= raysBtnX + raysBtnS && mouseY >= raysBtnY && mouseY <= raysBtnY + raysBtnS) {
                    p.raysOn = !p.raysOn;
                    uiConsumed = true;
                } else if (!isEditMenuMinimized && mouseX <= currentEditMenuX + currentEditMenuW) {
                    uiConsumed = true;
                    if (openEditUnitMenu >= 0 && mouseX >= editUnitMenuX && mouseX <= editUnitMenuX + editUnitMenuW
                            && mouseY >= editUnitMenuY && mouseY <= editUnitMenuY + editUnitMenuH) {
                        int chosenU = (int) ((mouseY - editUnitMenuY) / 16);
                        int maxU = (openEditUnitMenu == 4) ? 4 : (openEditUnitMenu == 3 ? 1 : 3);
                        if (chosenU >= 0 && chosenU < maxU) fieldUnits[openEditUnitMenu] = chosenU;
                        openEditUnitMenu = -1;
                    } else {
                        openEditUnitMenu = -1;
                        for (int i = 0; i < 6; i++) {
                            if (mouseX >= editBoxHits[i][0] && mouseX <= editBoxHits[i][0] + editBoxHits[i][2]
                                    && mouseY >= editBoxHits[i][1] && mouseY <= editBoxHits[i][1] + editBoxHits[i][3]) {
                                activeEditField = i;
                                isTypingPlanetEdit = true;
                                planetEditBuffer = "";
                                break;
                            }
                            if (i != 3 && mouseX >= unitBoxHits[i][0] && mouseX <= unitBoxHits[i][0] + unitBoxHits[i][2]
                                    && mouseY >= unitBoxHits[i][1] && mouseY <= unitBoxHits[i][1] + unitBoxHits[i][3]) {
                                openEditUnitMenu = (openEditUnitMenu == i) ? -1 : i;
                                break;
                            }
                            if (i < 3) {
                                double[] mults = {0.1, 0.5, 2.0, 5.0, 10.0};
                                for (int j = 0; j < 5; j++) {
                                    if (mouseX >= multBoxHits[i][j][0] && mouseX <= multBoxHits[i][j][0] + multBoxHits[i][j][2]
                                            && mouseY >= multBoxHits[i][j][1] && mouseY <= multBoxHits[i][j][1] + multBoxHits[i][j][3]) {
                                        if (i == 0) p.mass = Math.max(1e-7, p.mass * mults[j]);
                                        else if (i == 1) { p.radius = Math.max(0.01, p.radius * mults[j]); p.targetRadius = p.radius; }
                                        else if (i == 2) p.rotationPeriod *= mults[j];
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!uiConsumed) {
                if (isDeleteToolActive) {
                    Planet hit = CameraManager.getRaycastHit();
                    if (hit != null) {
                        if (CameraManager.lockedTarget == hit) {
                            CameraManager.isLocked = false;
                            CameraManager.lockedTarget = null;
                        }
                        if (orbitTarget == hit) orbitTarget = null;
                        hit.clearTrail();
                        planets.remove(hit);
                        planetsNeedSort = true;
                        SpaceUI.addNotification("Deleted: " + hit.name);
                    } else {
                        CameraManager.deleteDebrisAlongRay();
                    }
                } else if (isMoveToolActive) {
                    draggedPlanet = CameraManager.getRaycastHit();
                } else if (isPlacingPlanet) {
                    if (currentSpawnMode == SpawnMode.ORBIT && !planets.isEmpty()) {
                        Planet hitTarget = CameraManager.getRaycastHit();
                        if (orbitTarget == null || !planets.contains(orbitTarget)) {
                            orbitTarget = (hitTarget != null) ? hitTarget : findDefaultOrbitTarget();
                        } else if (hitTarget != null && hitTarget != orbitTarget) {
                            orbitTarget = hitTarget;
                        } else {
                            MenuItem item = menuItems[selectedPlanetIndex];
                            baseOrbitPos.set(getHologramPos(item.radius));
                            orbitInclination = 0.0;
                            orbitDragStartY = mouseY;
                            isDraggingOrbit = true;
                        }
                    } else {
                        MenuItem item = menuItems[selectedPlanetIndex];
                        Vector3d spawnPos = getHologramPos(item.radius);
                        if (!isOverlappingAnyPlanet(spawnPos, item.radius)) {
                            spawnPlanet(spawnPos, new Vector3d(0, 1, 0));
                            isPlacingPlanet = false;
                        } else {
                            SpaceUI.addNotification("Cannot spawn inside another object!");
                        }
                    }
                } else {
                    CameraManager.handleLeftClick();
                }
            }
        } else if (lmbDown && isLMBPressed) {
            if (isDraggingFpsOverlay) {
                fpsOverlayX = (int) Math.round(mouseX - fpsDragOffsetX);
                fpsOverlayY = (int) Math.round(mouseY - fpsDragOffsetY);
            } else if (isDraggingFsrCube) {
                updateFsrFromMouse(mouseX);
            } else if (isDraggingShaderCube) {
                updateShaderFromMouse(mouseX);
            } else if (isDraggingParticleCube) {
                updateParticleFromMouse(mouseX);
            } else if (isDraggingVolumeCube) {
                updateVolumeFromMouse(mouseX);
            } else if (isDraggingOrbit && orbitTarget != null) {
                orbitInclination = Math.max(-Math.PI * 0.49, Math.min(Math.PI * 0.49, (orbitDragStartY - mouseY) * 0.012));
            } else if (isDeleteToolActive && !isWelcomeMenuOpen) {
                boolean overMenu = (mouseY >= sHeight - 26)
                        || (isToolsMenuOpen && mouseX >= toolMenuX && mouseY >= toolMenuY)
                        || (isAddMenuOpen && mouseY >= sHeight - 26 - 135)
                        || (isEscMenuOpen && mouseX <= Math.max(260, (int) (sWidth * 0.28f)));
                if (!overMenu) {
                    CameraManager.deleteDebrisAlongRay();
                }
            }
        } else if (!lmbDown && isLMBPressed) {
            if (isDraggingOrbit && orbitTarget != null && planets.contains(orbitTarget)) {
                spawnPlanetWithOrbit(baseOrbitPos, orbitInclination);
                isPlacingPlanet = false;
                orbitTarget = null;
            }
            isDraggingOrbit = false;
            draggedPlanet = null;
            isDraggingFpsOverlay = false;
            isDraggingFsrCube = false;
            isDraggingShaderCube = false;
            isDraggingParticleCube = false;
            isDraggingVolumeCube = false;
        }

        isLMBPressed = lmbDown;
    }
}