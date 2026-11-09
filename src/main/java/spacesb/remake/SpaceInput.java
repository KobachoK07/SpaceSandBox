package spacesb.remake;

import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import spacesb.remake.render.SBoxVulkanCore;

import java.awt.Desktop;
import java.net.URI;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

import static spacesb.remake.SpaceEngine.*;

public class SpaceInput {

    private static long registeredScrollWindow = 0L;
    private static double lastMouseX = 0, lastMouseY = 0;
    private static int lastLogicalW = 854, lastLogicalH = 480;

    public static double getLastMouseX() { return lastMouseX; }
    public static double getLastMouseY() { return lastMouseY; }

    private static void openUrl(String url) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    new ProcessBuilder("explorer", url).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            } catch (Exception ignored) {}
        }, "SBox-URL-Opener").start();
    }

    public static void applySimRename() {
        if (!isRenamingSim) return;
        isRenamingSim = false;
        if (renamingSimIndex >= 0 && renamingSimIndex < SimulationManager.savedSimulations.size()) {
            SimulationManager.SavedSimEntry entry = SimulationManager.savedSimulations.get(renamingSimIndex);
            if (simRenameBuffer != null && !simRenameBuffer.trim().isEmpty()) {
                SimulationManager.renameSimulation(entry, simRenameBuffer.trim());
                SpaceUI.addNotification("Renamed: " + entry.name);
            }
        }
        renamingSimIndex = -1;
    }

    private static void ensureScrollCallback(long window) {
        if (window == 0L || registeredScrollWindow == window) return;
        registeredScrollWindow = window;
        GLFW.glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            if (isWelcomeMenuOpen || isPhotoModeActive || yoffset == 0.0) return;

            int escPanelW = Math.max(260, (int) (lastLogicalW * 0.28f));
            if (isEscMenuOpen && !CameraManager.isLocked && lastMouseX <= escPanelW && lastMouseY < lastLogicalH - 26) {
                if (isSimulationsMenuOpen) {
                    simListScrollY = Math.max(0.0, Math.min(simListMaxScroll, simListScrollY - yoffset * 24.0));
                } else if (!isResolutionMenuOpen && !isMathBitsMenuOpen && !SpaceUI.isLanguageMenuOpen && openColorPicker == 0) {
                    escMenuScrollY = Math.max(0.0, Math.min(escMenuMaxScroll, escMenuScrollY - yoffset * 18.0));
                }
                return;
            }

            if (isAddMenuOpen && lastMouseY >= lastLogicalH - 26 - 135 && lastMouseY < lastLogicalH - 26 && lastMouseX < lastLogicalW - 185) {
                menuScrollY = Math.max(0.0, menuScrollY - yoffset * 22.0);
                return;
            }

            if (CameraManager.isLocked && !isEditMenuMinimized
                    && lastMouseX <= currentEditMenuX + currentEditMenuW && lastMouseY < lastLogicalH - 26) {
                if (isObjectPreviewOpen && isInside(lastMouseX, lastMouseY, previewBoxHit)) {
                    if (yoffset > 0) objectPreviewZoom = Math.max(2.0f, objectPreviewZoom / 1.14f);
                    else if (yoffset < 0) objectPreviewZoom = Math.min(20.0f, objectPreviewZoom * 1.14f);
                    return;
                }
                double maxScroll = isObjectPreviewOpen ? 290.0 : 140.0;
                editMenuScrollY = Math.max(0.0, Math.min(maxScroll, editMenuScrollY - yoffset * 18.0));
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

    private static void updatePhysicsModeFromMouse(double mouseX) {
        int tx = escPhysicsModeTrackHit[0], tw = Math.max(1, escPhysicsModeTrackHit[2]);
        physicsModeSliderPos = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        physicsSimMode = (physicsModeSliderPos >= 0.5f) ? 1 : 0;
    }

    private static void updateVolumeFromMouse(double mouseX) {
        int tx = escVolumeTrackHit[0], tw = Math.max(1, escVolumeTrackHit[2]);
        musicVolume = Math.max(0.0f, Math.min(1.0f, (float) ((mouseX - tx) / tw)));
        SBoxAudioEngine.setMusicVolume(musicVolume);
    }

    private static boolean isInside(double mx, double my, int[] rect) {
        return rect[2] > 0 && rect[3] > 0 && mx >= rect[0] && mx <= rect[0] + rect[2] && my >= rect[1] && my <= rect[1] + rect[3];
    }

    public static void processInput(long window, int sWidth, int sHeight, int scale) {
        lastLogicalW = sWidth;
        lastLogicalH = sHeight;
        ensureScrollCallback(window);

        if (isAddMenuOpen || isToolsMenuOpen) {
            isEscMenuOpen = false;
            isResolutionMenuOpen = false;
            isMathBitsMenuOpen = false;
            SpaceUI.isLanguageMenuOpen = false;
            openColorPicker = 0;
            if (isRenamingSim) applySimRename();
        }

        for (int k = 32; k <= GLFW.GLFW_KEY_LAST; k++) {
            boolean down = GLFW.glfwGetKey(window, k) == GLFW.GLFW_PRESS;
            justPressed[k] = down && !keyStates[k];
            keyStates[k] = down;
        }

        if (isRenamingSim) {
            if (justPressed[GLFW.GLFW_KEY_BACKSPACE] && !simRenameBuffer.isEmpty()) {
                simRenameBuffer = simRenameBuffer.substring(0, simRenameBuffer.length() - 1);
            }
            if (justPressed[GLFW.GLFW_KEY_ENTER] || justPressed[GLFW.GLFW_KEY_KP_ENTER]) {
                applySimRename();
                return;
            }
            if (justPressed[GLFW.GLFW_KEY_ESCAPE]) {
                isRenamingSim = false;
                renamingSimIndex = -1;
                escPressed = true;
                return;
            }
            boolean shift = keyStates[GLFW.GLFW_KEY_LEFT_SHIFT] || keyStates[GLFW.GLFW_KEY_RIGHT_SHIFT];
            if (simRenameBuffer.length() < 26) {
                for (int k = GLFW.GLFW_KEY_A; k <= GLFW.GLFW_KEY_Z; k++) {
                    if (justPressed[k]) {
                        char c = (char) ((shift ? 'A' : 'a') + (k - GLFW.GLFW_KEY_A));
                        simRenameBuffer += c;
                    }
                }
                for (int k = GLFW.GLFW_KEY_0; k <= GLFW.GLFW_KEY_9; k++) {
                    if (justPressed[k]) simRenameBuffer += (char) ('0' + (k - GLFW.GLFW_KEY_0));
                }
                for (int k = GLFW.GLFW_KEY_KP_0; k <= GLFW.GLFW_KEY_KP_9; k++) {
                    if (justPressed[k]) simRenameBuffer += (char) ('0' + (k - GLFW.GLFW_KEY_KP_0));
                }
                if (justPressed[GLFW.GLFW_KEY_SPACE] && !simRenameBuffer.isEmpty()) {
                    simRenameBuffer += " ";
                }
                if (justPressed[GLFW.GLFW_KEY_MINUS] || justPressed[GLFW.GLFW_KEY_KP_SUBTRACT]) {
                    simRenameBuffer += shift ? "_" : "-";
                }
                if (justPressed[GLFW.GLFW_KEY_PERIOD] || justPressed[GLFW.GLFW_KEY_KP_DECIMAL]) {
                    simRenameBuffer += ".";
                }
            }
            return;
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
                isDraggingObjectPreview = false;
            } else if (isDeleteToolActive) {
                isDeleteToolActive = false;
            } else if (isMoveToolActive) {
                isMoveToolActive = false;
                draggedPlanet = null;
            } else if (!isWelcomeMenuOpen) {
                isEscMenuOpen = !isEscMenuOpen;
                isResolutionMenuOpen = false;
                isMathBitsMenuOpen = false;
                SpaceUI.isLanguageMenuOpen = false;
                openColorPicker = 0;
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
        boolean rmbJustPressed = rmbDown && !isRMBPressed;
        isRMBPressed = rmbDown;

        if (rmbJustPressed && !isWelcomeMenuOpen && !isPhotoModeActive
                && isPlacingPlanet && currentSpawnMode == SpawnMode.ORBIT && !planets.isEmpty()) {
            boolean overAnyUi = (mouseY >= sHeight - 26)
                    || (isToolsMenuOpen && mouseX >= toolMenuX && mouseY >= toolMenuY)
                    || (isAddMenuOpen && mouseY >= sHeight - 26 - 135)
                    || (isEscMenuOpen && mouseX <= Math.max(260, (int) (sWidth * 0.28f)));
            if (!overAnyUi) {
                Planet hitTarget = CameraManager.getRaycastHit();
                if (hitTarget != null) {
                    orbitTarget = hitTarget;
                    SpaceUI.addNotification("Orbit Target: " + hitTarget.name);
                }
            }
        }

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

            if (isRenamingSim) {
                applySimRename();
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
                        isMathBitsMenuOpen = false;
                        SpaceUI.isLanguageMenuOpen = false;
                        openColorPicker = 0;
                    }
                } else if (mouseX >= toolsBtnX && mouseX <= toolsBtnX + toolsBtnW && mouseY >= toolsBtnY && mouseY <= toolsBtnY + toolsBtnH) {
                    isToolsMenuOpen = !isToolsMenuOpen;
                    if (isToolsMenuOpen) {
                        isAddMenuOpen = false;
                        isEscMenuOpen = false;
                        isResolutionMenuOpen = false;
                        isMathBitsMenuOpen = false;
                        SpaceUI.isLanguageMenuOpen = false;
                        openColorPicker = 0;
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

                // Верхние кнопки ESC (Новая симуляция / Симуляции) доступны всегда
                if (isInside(mouseX, mouseY, escTopBtnHits[0])) {
                    openColorPicker = 0;
                    isSimulationsMenuOpen = false;
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
                } else if (isInside(mouseX, mouseY, escTopBtnHits[1])) {
                    openColorPicker = 0;
                    isResolutionMenuOpen = false;
                    isMathBitsMenuOpen = false;
                    SpaceUI.isLanguageMenuOpen = false;
                    isSimulationsMenuOpen = !isSimulationsMenuOpen;
                    if (isSimulationsMenuOpen) {
                        simMenuAnimProgress = 0.0;
                        SimulationManager.refreshSavedSimulations();
                    }
                } else if (isSimulationsMenuOpen) {
                    if (isInside(mouseX, mouseY, escSimSubTabHits[0])) {
                        simMenuTab = 0;
                        simListScrollY = 0.0;
                    } else if (isInside(mouseX, mouseY, escSimSubTabHits[1])) {
                        simMenuTab = 1;
                        simListScrollY = 0.0;
                        SimulationManager.refreshSavedSimulations();
                    } else if (simMenuTab == 0) {
                        if (isInside(mouseX, mouseY, escSimItemHits[0])) {
                            selectedPresetSimIndex = 0;
                        } else if (isInside(mouseX, mouseY, escSimPresetLoadBtnHit)) {
                            SimulationManager.loadSolarSystemPreset();
                            isEscMenuOpen = false;
                            isSimulationsMenuOpen = false;
                        } else if (isInside(mouseX, mouseY, escExitBtnHit)) {
                            saveSettings();
                            SBoxAudioEngine.shutdown();
                            System.exit(0);
                        }
                    } else {
                        if (isInside(mouseX, mouseY, escSimNewSaveBtnHit)) {
                            SimulationManager.SavedSimEntry created = SimulationManager.saveCurrentSimulation(null, null);
                            if (created != null) {
                                selectedSavedSimIndex = 0;
                                isRenamingSim = true;
                                renamingSimIndex = 0;
                                simRenameBuffer = created.name;
                                simListScrollY = 0.0;
                                SpaceUI.addNotification("Saved! Type name & press Enter");
                            }
                        } else if (isInside(mouseX, mouseY, escSimSavedBottomBtnHits[0])) {
                            // 1 ряд 1 кнопка: Загрузить симуляцию
                            if (selectedSavedSimIndex >= 0 && selectedSavedSimIndex < SimulationManager.savedSimulations.size()) {
                                SimulationManager.loadSavedSimulation(SimulationManager.savedSimulations.get(selectedSavedSimIndex));
                                isEscMenuOpen = false;
                                isSimulationsMenuOpen = false;
                            }
                        } else if (isInside(mouseX, mouseY, escSimSavedBottomBtnHits[1])) {
                            // 1 ряд 2 кнопка: Перезаписать симуляцию (или создать новую, если список пуст)
                            SimulationManager.SavedSimEntry target = (selectedSavedSimIndex >= 0 && selectedSavedSimIndex < SimulationManager.savedSimulations.size())
                                    ? SimulationManager.savedSimulations.get(selectedSavedSimIndex) : null;
                            SimulationManager.SavedSimEntry saved = SimulationManager.saveCurrentSimulation(target != null ? target.name : null, target);
                            if (saved != null) {
                                int idx = SimulationManager.savedSimulations.indexOf(saved);
                                if (idx < 0) idx = 0;
                                selectedSavedSimIndex = idx;
                                isRenamingSim = true;
                                renamingSimIndex = idx;
                                simRenameBuffer = saved.name;
                                SpaceUI.addNotification("Saved! Type name & press Enter");
                            }
                        } else if (isInside(mouseX, mouseY, escSimSavedBottomBtnHits[2])) {
                            // 2 ряд 1 кнопка: Удалить симуляцию
                            if (selectedSavedSimIndex >= 0 && selectedSavedSimIndex < SimulationManager.savedSimulations.size()) {
                                SimulationManager.SavedSimEntry del = SimulationManager.savedSimulations.get(selectedSavedSimIndex);
                                SpaceUI.addNotification("Deleted: " + del.name);
                                SimulationManager.deleteSavedSimulation(del);
                                selectedSavedSimIndex = Math.max(0, Math.min(selectedSavedSimIndex, SimulationManager.savedSimulations.size() - 1));
                            }
                        } else if (isInside(mouseX, mouseY, escSimSavedBottomBtnHits[3])) {
                            // 2 ряд 2 кнопка: Корневая папка
                            SimulationManager.openSimulationsFolder();
                        } else {
                            int maxItems = Math.min(SimulationManager.savedSimulations.size(), escSimItemHits.length);
                            for (int i = 0; i < maxItems; i++) {
                                if (isInside(mouseX, mouseY, escSimItemHits[i])) {
                                    long now = System.currentTimeMillis();
                                    if (i == lastClickedSimItemIndex && (now - lastSimItemClickMs) < 420L) {
                                        isRenamingSim = true;
                                        renamingSimIndex = i;
                                        simRenameBuffer = SimulationManager.savedSimulations.get(i).name;
                                    } else {
                                        selectedSavedSimIndex = i;
                                        lastClickedSimItemIndex = i;
                                        lastSimItemClickMs = now;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                } else if (openColorPicker != 0 && isInside(mouseX, mouseY, escColorPopupHit)) {
                    int cols = 8;
                    int cellW = (escColorPopupHit[2] - 8) / cols;
                    int cellH = (escColorPopupHit[3] - 8) / 2;
                    int cIdx = (int) ((mouseX - (escColorPopupHit[0] + 4)) / Math.max(1, cellW));
                    int rIdx = (int) ((mouseY - (escColorPopupHit[1] + 4)) / Math.max(1, cellH));
                    if (cIdx >= 0 && cIdx < cols && rIdx >= 0 && rIdx < 2) {
                        int pIdx = rIdx * cols + cIdx;
                        if (openColorPicker == 1 && pIdx < PALETTE_COLOR_1.length) {
                            uiColor1 = PALETTE_COLOR_1[pIdx];
                        } else if (openColorPicker == 2 && pIdx < PALETTE_COLOR_2.length) {
                            uiColor2 = PALETTE_COLOR_2[pIdx];
                        }
                    }
                    openColorPicker = 0;
                } else if (SpaceUI.isLanguageMenuOpen && isInside(mouseX, mouseY, SpaceUI.escLangDropdownHit)) {
                    int langItemH = 18;
                    int chosenLang = (int) ((mouseY - SpaceUI.escLangDropdownHit[1]) / langItemH);
                    if (chosenLang >= 0 && chosenLang < SpaceUI.LANGUAGE_NAMES.length) {
                        SpaceUI.currentLanguage = chosenLang;
                        SpaceUI.addNotification(SpaceUI.tr(0) + ": " + SpaceUI.LANGUAGE_NAMES[SpaceUI.currentLanguage]);
                    }
                    SpaceUI.isLanguageMenuOpen = false;
                } else if (isResolutionMenuOpen && isInside(mouseX, mouseY, escResDropdownHit)) {
                    int idx = (int) ((mouseY - escResDropdownHit[1]) / 16);
                    if (idx >= 0 && idx < RESOLUTIONS.length) {
                        resolutionIndex = idx;
                        applyDisplaySettings(window);
                    }
                    isResolutionMenuOpen = false;
                } else if (isMathBitsMenuOpen && isInside(mouseX, mouseY, escMathBitsDropdownHit)) {
                    int idx = (int) ((mouseY - escMathBitsDropdownHit[1]) / 16);
                    if (idx >= 0 && idx < MATH_BITS_OPTIONS.length) {
                        if (!SBoxVulkanCore.supportsFp64 && idx == 2) {
                            physicsPrecisionMode = 1;
                            SpaceUI.addNotification("GPU does not support native 64-bit: using 32+64 bit");
                        } else {
                            physicsPrecisionMode = idx;
                        }
                    }
                    isMathBitsMenuOpen = false;
                } else {
                    isResolutionMenuOpen = false;
                    isMathBitsMenuOpen = false;
                    SpaceUI.isLanguageMenuOpen = false;

                    if (isInside(mouseX, mouseY, escExitBtnHit)) {
                        saveSettings();
                        SBoxAudioEngine.shutdown();
                        System.exit(0);
                    } else if (isInside(mouseX, mouseY, escVisualTabHit)) {
                        openColorPicker = 0;
                        isVisualTabOpen = !isVisualTabOpen;
                    } else if (isInside(mouseX, mouseY, escPhysicsTabHit)) {
                        openColorPicker = 0;
                        isPhysicsTabOpen = !isPhysicsTabOpen;
                    } else if (isInside(mouseX, mouseY, escMiscTabHit)) {
                        openColorPicker = 0;
                        isMiscTabOpen = !isMiscTabOpen;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSettingHits[0])) {
                        openColorPicker = 0;
                        fsrEnabled = !fsrEnabled;
                    } else if (isVisualTabOpen && escFsrTrackHit[2] > 0 && mouseX >= escFsrTrackHit[0] && mouseX <= escFsrTrackHit[0] + escFsrTrackHit[2]
                            && mouseY >= escFsrTrackHit[1] - 2 && mouseY <= escFsrTrackHit[1] + escFsrTrackHit[3] + 2) {
                        openColorPicker = 0;
                        isDraggingFsrCube = true;
                        updateFsrFromMouse(mouseX);
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSettingHits[2])) {
                        openColorPicker = 0;
                        hdrEnabled = !hdrEnabled;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSettingHits[4])) {
                        openColorPicker = 0;
                        vsyncEnabled = !vsyncEnabled;
                        needsSwapchainRecreate = true;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSettingHits[5])) {
                        openColorPicker = 0;
                        isResolutionMenuOpen = true;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSettingHits[6])) {
                        openColorPicker = 0;
                        isFullscreen = !isFullscreen;
                        applyDisplaySettings(window);
                    } else if (isVisualTabOpen && escShaderTrackHit[2] > 0 && mouseX >= escShaderTrackHit[0] && mouseX <= escShaderTrackHit[0] + escShaderTrackHit[2]
                            && mouseY >= escShaderTrackHit[1] - 2 && mouseY <= escShaderTrackHit[1] + escShaderTrackHit[3] + 2) {
                        openColorPicker = 0;
                        isDraggingShaderCube = true;
                        updateShaderFromMouse(mouseX);
                    } else if (isVisualTabOpen && escParticleTrackHit[2] > 0 && mouseX >= escParticleTrackHit[0] && mouseX <= escParticleTrackHit[0] + escParticleTrackHit[2]
                            && mouseY >= escParticleTrackHit[1] - 2 && mouseY <= escParticleTrackHit[1] + escParticleTrackHit[3] + 2) {
                        openColorPicker = 0;
                        isDraggingParticleCube = true;
                        updateParticleFromMouse(mouseX);
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escSaturnRingsHit)) {
                        openColorPicker = 0;
                        enableSaturnRings = !enableSaturnRings;
                        PhysicsEngine.ensureSaturnRings(planets);
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escAccretionDiskHit)) {
                        openColorPicker = 0;
                        enableAccretionDisk = !enableAccretionDisk;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escColor1Hit)) {
                        openColorPicker = (openColorPicker == 1) ? 0 : 1;
                    } else if (isVisualTabOpen && isInside(mouseX, mouseY, escColor2Hit)) {
                        openColorPicker = (openColorPicker == 2) ? 0 : 2;
                    } else if (isPhysicsTabOpen && isInside(mouseX, mouseY, escMathBitsBtnHit)) {
                        openColorPicker = 0;
                        isMathBitsMenuOpen = true;
                    } else if (isPhysicsTabOpen && escPhysicsModeTrackHit[2] > 0
                            && mouseX >= escPhysicsModeTrackHit[0] && mouseX <= escPhysicsModeTrackHit[0] + escPhysicsModeTrackHit[2]
                            && mouseY >= escPhysicsModeTrackHit[1] - 2 && mouseY <= escPhysicsModeTrackHit[1] + escPhysicsModeTrackHit[3] + 2) {
                        openColorPicker = 0;
                        isDraggingPhysicsModeCube = true;
                        updatePhysicsModeFromMouse(mouseX);
                    } else if (isMiscTabOpen && isInside(mouseX, mouseY, escSettingHits[7])) {
                        openColorPicker = 0;
                        showFpsOverlay = !showFpsOverlay;
                    } else if (isMiscTabOpen && isInside(mouseX, mouseY, escSettingHits[8])) {
                        openColorPicker = 0;
                        photoModeFeatureEnabled = !photoModeFeatureEnabled;
                    } else if (isMiscTabOpen && escVolumeTrackHit[2] > 0 && mouseX >= escVolumeTrackHit[0] && mouseX <= escVolumeTrackHit[0] + escVolumeTrackHit[2]
                            && mouseY >= escVolumeTrackHit[1] - 2 && mouseY <= escVolumeTrackHit[1] + escVolumeTrackHit[3] + 2) {
                        openColorPicker = 0;
                        isDraggingVolumeCube = true;
                        updateVolumeFromMouse(mouseX);
                    } else if (isMiscTabOpen && isInside(mouseX, mouseY, SpaceUI.escLangBtnHit)) {
                        openColorPicker = 0;
                        SpaceUI.isLanguageMenuOpen = true;
                    } else {
                        openColorPicker = 0;
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
                        SpaceUI.showGravityGrid = !SpaceUI.showGravityGrid;
                        if (SpaceUI.showGravityGrid) showGrid = false;
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
                } else if (mouseX >= viewBtnX && mouseX <= viewBtnX + viewBtnS && mouseY >= viewBtnY && mouseY <= viewBtnY + viewBtnS) {
                    isObjectPreviewOpen = !isObjectPreviewOpen;
                    uiConsumed = true;
                } else if (mouseX >= flashBtnX && mouseX <= flashBtnX + flashBtnS && mouseY >= flashBtnY && mouseY <= flashBtnY + flashBtnS) {
                    p.flashlightOn = !p.flashlightOn;
                    uiConsumed = true;
                } else if (mouseX >= raysBtnX && mouseX <= raysBtnX + raysBtnS && mouseY >= raysBtnY && mouseY <= raysBtnY + raysBtnS) {
                    p.raysOn = !p.raysOn;
                    uiConsumed = true;
                } else if (!isEditMenuMinimized && mouseX <= currentEditMenuX + currentEditMenuW) {
                    uiConsumed = true;
                    if (isObjectPreviewOpen && isInside(mouseX, mouseY, previewBoxHit)) {
                        isDraggingObjectPreview = true;
                        previewLastMouseX = mouseX;
                        previewLastMouseY = mouseY;
                        openEditUnitMenu = -1;
                    } else if (openEditUnitMenu >= 0 && mouseX >= editUnitMenuX && mouseX <= editUnitMenuX + editUnitMenuW
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
                                    && mouseY >= editBoxHits[i][1] && mouseY <= unitBoxHits[i][1] + unitBoxHits[i][3]) {
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
                        if (orbitTarget == null || !planets.contains(orbitTarget)) {
                            orbitTarget = findDefaultOrbitTarget();
                        }
                        if (orbitTarget != null) {
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
            } else if (isDraggingObjectPreview) {
                objectPreviewYaw += (float) (mouseX - previewLastMouseX) * 0.85f;
                objectPreviewPitch = Math.max(-89.0f, Math.min(89.0f, objectPreviewPitch + (float) (mouseY - previewLastMouseY) * 0.85f));
                previewLastMouseX = mouseX;
                previewLastMouseY = mouseY;
            } else if (isDraggingFsrCube) {
                updateFsrFromMouse(mouseX);
            } else if (isDraggingShaderCube) {
                updateShaderFromMouse(mouseX);
            } else if (isDraggingParticleCube) {
                updateParticleFromMouse(mouseX);
            } else if (isDraggingPhysicsModeCube) {
                updatePhysicsModeFromMouse(mouseX);
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
            isDraggingObjectPreview = false;
            isDraggingFsrCube = false;
            isDraggingShaderCube = false;
            isDraggingParticleCube = false;
            isDraggingPhysicsModeCube = false;
            isDraggingVolumeCube = false;
        }

        isLMBPressed = lmbDown;
    }
}