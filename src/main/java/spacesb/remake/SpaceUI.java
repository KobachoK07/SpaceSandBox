package spacesb.remake;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static spacesb.remake.SpaceEngine.*;

public class SpaceUI {

    private static double editMenuAnimX = 0;
    private static double lastUiTime = 0;

    private static boolean initNotif = false;
    private static boolean prevShowGrid = true;
    private static boolean prevShowTrails = true;
    private static boolean prevPhysicsEnabled = true;
    private static boolean prevMoveTool = false;
    private static boolean prevPaused = false;
    private static boolean wasClearHoveredAndClicked = false;

    public static void addNotification(String text) {
        SpaceEngine.notifications.add(0, new SpaceEngine.Notification(text));
    }

    public static String formatTimeScale(double scale) {
        if (scale < 60) return String.format(Locale.US, "%.1f sec/sec", scale);
        if (scale < 3600) return String.format(Locale.US, "%.1f min/sec", scale / 60);
        if (scale < 86400) return String.format(Locale.US, "%.1f hr/sec", scale / 3600);
        if (scale < 31536000) return String.format(Locale.US, "%.1f days/sec", scale / 86400);
        return formatCompact(scale / 31536000) + " yrs/sec";
    }

    public static String formatFlightSpeed(double speedKmS) {
        if (speedKmS >= 299792.458) {
            return String.format(Locale.US, "%.2f c", speedKmS / 299792.458);
        } else if (speedKmS >= 1.0) {
            return String.format(Locale.US, "%.1f km/s", speedKmS);
        } else {
            double speedMs = speedKmS * 1000.0;
            if (speedMs >= 1.0) {
                return String.format(Locale.US, "%.1f m/s", speedMs);
            } else {
                return String.format(Locale.US, "%.1f km/h", speedKmS * 3600.0);
            }
        }
    }

    public static String formatCompact(double v) {
        if (v == 0.0) return "0";
        double abs = Math.abs(v);
        if (abs < 0.01) return String.format(Locale.US, "%.2e", v);
        if (abs >= 1e12) return String.format(Locale.US, "%.2e", v);
        if (abs >= 1e9) return String.format(Locale.US, "%.2f B", v / 1e9);
        if (abs >= 1e6) return String.format(Locale.US, "%.2f M", v / 1e6);
        if (abs >= 1e3) return String.format(Locale.US, "%.2f k", v / 1e3);
        return String.format(Locale.US, "%.2f", v);
    }

    public static String formatDistanceDynamic(double km) {
        if (km < 1.0) return String.format(Locale.US, "%,.0f m", km * 1000.0);
        else if (km < 1_000_000.0) return String.format(Locale.US, "%,.1f km", km);
        else if (km < 149597870.0) return String.format(Locale.US, "%,.2f M km", km / 1_000_000.0);
        else return String.format(Locale.US, "%,.2f AU", km / 149597870.0);
    }

    public static void onScroll(double yoffset) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() == null || isWelcomeMenuOpen) return;

        double mX = client.mouse.getX() * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth();
        double mY = client.mouse.getY() * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight();

        if (CameraManager.isLocked && CameraManager.lockedTarget != null && !isEditMenuMinimized) {
            if (mX <= currentEditMenuW && mY <= client.getWindow().getScaledHeight() - 22) {
                editMenuScrollY -= yoffset * 25.0;
                int contentH = 340, headerH = 35, panelH = client.getWindow().getScaledHeight() - 22;
                int maxScroll = Math.max(0, contentH - (panelH - headerH));
                if (editMenuScrollY > maxScroll) editMenuScrollY = maxScroll;
                if (editMenuScrollY < 0) editMenuScrollY = 0;
                return;
            }
        }

        if (isAddMenuOpen && mX < client.getWindow().getScaledWidth() - 170 && mY >= client.getWindow().getScaledHeight() - 122) {
            menuScrollY -= yoffset * 15.0;
            if (menuScrollY < 0) menuScrollY = 0;
            return;
        }

        if (CameraManager.isLocked && targetOrbitDistance > 0) {
            if (yoffset > 0) targetOrbitDistance *= 0.85;
            else if (yoffset < 0) targetOrbitDistance *= 1.15;

            double minZoom = CameraManager.lockedTarget != null ? CameraManager.lockedTarget.radius * 1.2 : 1.0;
            if (targetOrbitDistance < minZoom) targetOrbitDistance = minZoom;
            return;
        }

        if (isPlacingPlanet && (currentSpawnMode == SpawnMode.LAUNCH || (currentSpawnMode == SpawnMode.ORBIT && !autoOrbitSpeed))) {
            if (yoffset != 0) {
                isCustomSpeed = true;
                if (yoffset > 0) inputSpeed *= 1.25;
                else if (yoffset < 0) inputSpeed /= 1.25;
                if (inputSpeed < 0.001) inputSpeed = 0.001;
                capSpeed();
            }
            return;
        }

        SpaceEngine.lastScrollTime = System.currentTimeMillis();
        if (yoffset > 0) playerFlightSpeed *= 1.25;
        else if (yoffset < 0) playerFlightSpeed /= 1.25;

        double maxSpeed = 100.0 * 299792.458;
        if (playerFlightSpeed > maxSpeed) playerFlightSpeed = maxSpeed;
        if (playerFlightSpeed < 0.0001) playerFlightSpeed = 0.0001;
    }

    public static void renderUI(DrawContext context) {
        if (menuItems == null || menuItems.length == 0) return;
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();

        double currentRealTime = System.currentTimeMillis() / 1000.0;
        if (lastUiTime == 0) lastUiTime = currentRealTime;
        double dtUI = currentRealTime - lastUiTime;
        lastUiTime = currentRealTime;

        if (!initNotif) {
            addNotification("SpaceSB Engine Active");
            initNotif = true;
        }

        if (prevShowGrid != showGrid) { addNotification("Grid: " + (showGrid ? "ON" : "OFF")); prevShowGrid = showGrid; }
        if (prevShowTrails != showTrails) { addNotification("Trails: " + (showTrails ? "ON" : "OFF")); prevShowTrails = showTrails; }
        if (prevPhysicsEnabled != physicsEnabled) { addNotification("Physics: " + (physicsEnabled ? "ON" : "OFF")); prevPhysicsEnabled = physicsEnabled; }
        if (prevMoveTool != isMoveToolActive) { addNotification("Move Tool: " + (isMoveToolActive ? "ON" : "OFF")); prevMoveTool = isMoveToolActive; }
        if (prevPaused != isPaused) { addNotification(isPaused ? "Simulation Paused" : "Simulation Resumed"); prevPaused = isPaused; }

        if (!isLMBPressed) {
            wasClearHoveredAndClicked = false;
        }

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, 1000.0F, 21000.0F);
        RenderSystem.setProjectionMatrix(ortho, VertexSorter.BY_Z);
        context.getMatrices().push();
        context.getMatrices().peek().getPositionMatrix().identity();
        context.getMatrices().translate(0.0F, 0.0F, -11000.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        int barHeight = 26, rectY = height - barHeight;

        if (!CameraManager.isLocked) {
            long elapsedScroll = System.currentTimeMillis() - lastScrollTime;
            if (elapsedScroll < 2000 && !isWelcomeMenuOpen) {
                float alpha = elapsedScroll < 1500 ? 1.0f : 1.0f - (elapsedScroll - 1500) / 500.0f;
                int aInt = (int)(alpha * 255);
                if (aInt > 5) {
                    String speedTxt = "Speed: " + formatFlightSpeed(playerFlightSpeed);
                    int tw = client.textRenderer.getWidth(speedTxt);
                    int sx = width / 2 - tw / 2;
                    int sy = 25;
                    context.drawText(client.textRenderer, speedTxt, sx, sy, (aInt << 24) | 0xFFFFFF, true);
                }
            }
        }

        if (showFpsOverlay) {
            int fps = client.getCurrentFps();
            String fpsText = "FPS: " + fps;
            String tickText = String.format(Locale.US, "Physics Tick: %.2f ms", lastPhysicsTickMs);
            String objectsText = "Planets: " + planets.size() + " | Particles: " + particles.size();
            context.fill(5, 5, 180, 45, 0x99000000);
            context.drawText(client.textRenderer, fpsText, 10, 10, fps > 30 ? 0x55FF55 : 0xFF5555, false);
            context.drawText(client.textRenderer, tickText, 10, 22, lastPhysicsTickMs < 5.0 ? 0x55FF55 : 0xFFFF55, false);
            context.drawText(client.textRenderer, objectsText, 10, 34, 0xAAAAAA, false);
        }

        if (isWelcomeMenuOpen) {
            context.fill(0, 0, width, height, 0xCC000000);
            int panelW = 270, panelH = 245, availableH = rectY - 20 - 30;
            float scale = 1.0f; if (availableH < panelH) scale = Math.max(0.3f, (float) availableH / panelH);
            int scaledW = (int)(panelW * scale), panelX = (width - scaledW) / 2, panelY = 30;

            context.getMatrices().push(); context.getMatrices().translate(panelX, panelY, 0); context.getMatrices().scale(scale, scale, 1.0f);
            context.fill(0, 0, panelW, panelH, 0xFA060606); drawOutline(context, 0, 0, panelW, panelH, 0xFF2A2A2A);
            int logoW = 160, logoH = 58, logoX = (panelW - logoW) / 2, logoY = 10;
            drawIcon(context, LOGO_TEX, logoX, logoY, logoW, logoH);
            int warnY = logoY + logoH + 4; String warnTxt = "WARNING!";
            context.drawText(client.textRenderer, warnTxt, (panelW - client.textRenderer.getWidth(warnTxt))/2, warnY, 0xFFFF5555, false);

            int textY = warnY + 12;
            String body = "This mod features realistic simulations of open space, extreme cosmic scales, total darkness, and depictions of colossal astronomical objects. If you suffer from astrophobia, megalophobia, thalassophobia, or heightened anxiety, we strongly advise against playing this mod. The gameplay can trigger severe panic attacks and intense psychological distress; please do not put your mental and physical health at risk.";
            for(OrderedText line : client.textRenderer.wrapLines(StringVisitable.plain(body), panelW - 20)) { context.drawText(client.textRenderer, line, 10, textY, 0xFFAAAAAA, false); textY += client.textRenderer.fontHeight + 2; }

            int gap = 4; modBtnX = 12; modBtnY = panelH - modBtnS - 10; tgBtnX = modBtnX + modBtnS + gap; tgBtnY = modBtnY; daBtnX = tgBtnX + modBtnS + gap; daBtnY = modBtnY;
            drawIcon(context, MODRINTH_TEX, modBtnX, modBtnY, modBtnS, modBtnS); drawIcon(context, TELEGRAM_TEX, tgBtnX, tgBtnY, tgBtnS, tgBtnS); drawIcon(context, DONATE_TEX, daBtnX, daBtnY, daBtnS, daBtnS);
            String closeTxt = "I Understand"; closeBtnW = client.textRenderer.getWidth(closeTxt) + 20; closeBtnH = 26; closeBtnX = panelW - closeBtnW - 10; closeBtnY = panelH - closeBtnH - 10;
            context.fill(closeBtnX, closeBtnY, closeBtnX + closeBtnW, closeBtnY + closeBtnH, 0xFF111111); drawOutline(context, closeBtnX, closeBtnY, closeBtnW, closeBtnH, 0xFF333333);
            context.drawText(client.textRenderer, closeTxt, closeBtnX + 10, closeBtnY + (closeBtnH - client.textRenderer.fontHeight)/2 + 1, 0xFFFFFFFF, false);
            context.getMatrices().pop();
        }

        if (CameraManager.isLocked && CameraManager.lockedTarget != null && !isWelcomeMenuOpen) {
            int panelW = 180; currentEditMenuW = panelW;
            double targetX = isEditMenuMinimized ? -panelW : 0; editMenuAnimX += (targetX - editMenuAnimX) * Math.min(1.0, dtUI * 15.0);
            int currentX = (int) Math.round(editMenuAnimX); currentEditMenuX = currentX; int panelH = rectY;

            context.fill(currentX, 0, currentX + panelW, panelH, 0xFA060606); context.fill(currentX + panelW - 1, 0, currentX + panelW, panelH, 0xFF2A2A2A);

            minBtnW = 14; minBtnH = 28; minBtnX = currentX + panelW; minBtnY = 10;
            context.fill(minBtnX, minBtnY, minBtnX + minBtnW, minBtnY + minBtnH, 0xFA060606); drawOutline(context, minBtnX, minBtnY, minBtnW, minBtnH, 0xFF2A2A2A);
            context.fill(minBtnX, minBtnY + 1, minBtnX + 1, minBtnY + minBtnH - 1, 0xFA060606);
            context.drawText(client.textRenderer, isEditMenuMinimized ? ">" : "<", minBtnX + 3, minBtnY + 10, 0xFFFFFFFF, false);

            Planet p = CameraManager.lockedTarget;

            flashBtnX = minBtnX + minBtnW + 4;
            flashBtnY = 10;

            if (!editFlashlight) RenderSystem.setShaderColor(0.35f, 0.35f, 0.35f, 1.0f);
            drawIcon(context, ICON_FLASHLIGHT, flashBtnX, flashBtnY, flashBtnS, flashBtnS);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            raysBtnX = flashBtnX + flashBtnS + 4;
            raysBtnY = 10;
            if (!editRays) RenderSystem.setShaderColor(0.35f, 0.35f, 0.35f, 1.0f);
            drawIcon(context, ICON_RAYS, raysBtnX, raysBtnY, raysBtnS, raysBtnS);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

            context.drawText(client.textRenderer, "Edit: " + p.name, currentX + 8, 15, 0xFFFFFFFF, false);

            if (!isEditMenuMinimized) {
                int headerH = 35; context.enableScissor(currentX, headerH, currentX + panelW, panelH);
                String[] labels = {"Mass", "Diameter", "Rotation", "Tilt", "Velocity", "Temp"};
                String[][] units = {{"Earths", "Suns", "kg"}, {"km", "Earths", "Suns"}, {"hours", "days", "years"}, {"deg"}, {"km/s", "m/s", "km/h", "c"}, {"C", "K", "F"}};
                int yOff = headerH + 10 - (int)editMenuScrollY;

                for (int i = 0; i < 6; i++) {
                    int blockH = (i < 3) ? 45 : 25;
                    context.fill(currentX + 4, yOff, currentX + panelW - 4, yOff + blockH, 0xFF111111); drawOutline(context, currentX + 4, yOff, panelW - 8, blockH, 0xFF2A2A2A);
                    context.drawText(client.textRenderer, labels[i] + ":", currentX + 5, yOff + 8, 0xFFAAAAAA, false);

                    int unitW = 38;
                    int unitX = currentX + panelW - unitW - 4;
                    int boxX = currentX + 60;
                    int boxW = unitX - boxX - 4;

                    int boxH = 14, boxY = yOff + 5;
                    editBoxHits[i][0] = boxX; editBoxHits[i][1] = boxY; editBoxHits[i][2] = boxW; editBoxHits[i][3] = boxH;
                    unitBoxHits[i][0] = unitX; unitBoxHits[i][1] = boxY; unitBoxHits[i][2] = unitW; unitBoxHits[i][3] = boxH;

                    boolean isActive = (activeEditField == i && isTypingPlanetEdit);
                    context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF080808); drawOutline(context, boxX, boxY, boxW, boxH, isActive ? 0xFF55FF55 : 0xFF555555);

                    String valStr = "";
                    if (isActive) valStr = planetEditBuffer + "_";
                    else {
                        double v = 0; int u = fieldUnits[i];
                        if (i == 0) { if (u == 0) v = p.mass; else if (u == 1) v = p.mass / 333000.0; else if (u == 2) v = p.mass * 5.9722e24; }
                        else if (i == 1) { if (u == 0) v = p.radius * 2.0 * 1260.0; else if (u == 1) v = p.radius * 2.0 * 1260.0 / 12742.0; else if (u == 2) v = p.radius * 2.0 * 1260.0 / 1392700.0; }
                        else if (i == 2) { if (u == 0) v = p.rotationPeriod; else if (u == 1) v = p.rotationPeriod / 24.0; else if (u == 2) v = p.rotationPeriod / 8760.0; }
                        else if (i == 3) { v = p.tilt; }
                        else if (i == 4) { double len = p.vel.length(); if (u == 0) v = len * 1260.0; else if (u == 1) v = len * 1260000.0; else if (u == 2) v = len * 1260.0 * 3600.0; else if (u == 3) v = len * 1260.0 / 299792.458; }
                        else if (i == 5) { if (u == 0) v = p.temperature; else if (u == 1) v = p.temperature + 273.15; else if (u == 2) v = p.temperature * 1.8 + 32.0; }

                        valStr = formatCompact(v);
                    }

                    if (isActive) {
                        while (client.textRenderer.getWidth(valStr) > boxW - 4 && valStr.length() > 1) {
                            valStr = valStr.substring(1);
                        }
                    }

                    context.drawText(client.textRenderer, valStr, boxX + 2, boxY + 3, 0xFFFFFFFF, false);

                    context.fill(unitX, boxY, unitX + unitW, boxY + boxH, 0xFF222222); drawOutline(context, unitX, boxY, unitW, boxH, 0xFF555555);
                    String uStr = units[i][fieldUnits[i]]; context.drawText(client.textRenderer, uStr, unitX + (unitW - client.textRenderer.getWidth(uStr))/2, boxY + 3, 0xFFAAAAAA, false);

                    if (i < 3) {
                        int mx = currentX + 5, my = yOff + 25; String[] multLabels = {"x0.1", "x0.5", "x2", "x5", "x10"};
                        for (int j = 0; j < 5; j++) {
                            int mw = client.textRenderer.getWidth(multLabels[j]) + 2, mh = 14;
                            multBoxHits[i][j][0] = mx; multBoxHits[i][j][1] = my; multBoxHits[i][j][2] = mw; multBoxHits[i][j][3] = mh;
                            context.fill(mx, my, mx + mw, my + mh, 0xFF1A1A1A); drawOutline(context, mx, my, mw, mh, 0xFF444444);
                            context.drawText(client.textRenderer, multLabels[j], mx + 1, my + 3, 0xFFFFFFFF, false); mx += mw + 2;
                        }
                    }
                    yOff += blockH + 5;
                }
                context.disableScissor();

                int contentH = 260;
                if (contentH > panelH - headerH) {
                    int scrollTrackX = currentX + panelW - 4, scrollTrackY = headerH, scrollTrackH = panelH - headerH;
                    context.fill(scrollTrackX, scrollTrackY, scrollTrackX + 3, scrollTrackY + scrollTrackH, 0xFF111111);
                    int thumbH = Math.max(20, (int)((float)(panelH - headerH) / contentH * scrollTrackH));
                    int thumbY = scrollTrackY + (int)((editMenuScrollY / (float)(contentH - (panelH - headerH))) * (scrollTrackH - thumbH));
                    context.fill(scrollTrackX, thumbY, scrollTrackX + 3, thumbY + thumbH, 0xFF555555);
                }
            }
        }

        if (isToolsMenuOpen && !isWelcomeMenuOpen) {
            toolMenuW = 140; toolMenuH = 113; toolMenuX = width - toolMenuW; toolMenuY = rectY - toolMenuH;
            context.fill(toolMenuX, toolMenuY, width, rectY, 0xFA060606); context.fill(toolMenuX, toolMenuY, width, toolMenuY + 1, 0xFF1A1A1A); context.fill(toolMenuX, toolMenuY, toolMenuX + 1, rectY, 0xFF1A1A1A);
            context.drawText(client.textRenderer, "Tools", toolMenuX + 10, toolMenuY + 5, 0xFFAAAAAA, false);
            int btnW = toolMenuW - 16, btnX = toolMenuX + 8, btnH = 16;

            moveToolY = toolMenuY + 20;
            gridToolY = toolMenuY + 38;
            trailToolY = toolMenuY + 56;
            physicsToolY = toolMenuY + 74;
            clearToolY = toolMenuY + 92;

            drawMenuToggle(context, client.textRenderer, "Move Planets", btnX, moveToolY, btnW, btnH, isMoveToolActive);
            drawMenuToggle(context, client.textRenderer, "Show Grid", btnX, gridToolY, btnW, btnH, showGrid);
            drawMenuToggle(context, client.textRenderer, "Show Trails", btnX, trailToolY, btnW, btnH, showTrails);
            drawMenuToggle(context, client.textRenderer, "Physics", btnX, physicsToolY, btnW, btnH, physicsEnabled);
            drawMenuAction(context, client.textRenderer, "Clear Universe", btnX, clearToolY, btnW, btnH, 0xFFFFAAAA);

            double mX = client.mouse.getX() * width / (double) client.getWindow().getWidth();
            double mY = client.mouse.getY() * height / (double) client.getWindow().getHeight();
            if (mX >= btnX && mX <= btnX + btnW && mY >= clearToolY && mY <= clearToolY + btnH) {
                if (isLMBPressed && !wasClearHoveredAndClicked) {
                    addNotification("Universe Cleared");
                    wasClearHoveredAndClicked = true;
                }
            }
        }

        if (isAddMenuOpen && !isWelcomeMenuOpen) {
            int menuHeight = 100, menuY = rectY - menuHeight;
            context.fill(0, menuY, width, rectY, 0xFA060606); context.fill(0, menuY, width, menuY + 1, 0xFF1A1A1A);

            int catX = 10, catY = menuY + 5, catSize = 18; catHitY = catY; catHitH = catSize;
            context.fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3;

            for (int c = 0; c < 5; c++) {
                int w;
                if (c == 0) {
                    String text = "ALL"; w = client.textRenderer.getWidth(text) + 12; catHitX[c] = catX; catHitW[c] = w;
                    int textColor = (currentCategory == c) ? 0xFF666666 : 0xFFFFFFFF;
                    context.drawText(client.textRenderer, text, catX + 6, catY + (catSize - client.textRenderer.fontHeight) / 2 + 1, textColor, false); catX += w;
                } else {
                    w = catSize + 12; catHitX[c] = catX; catHitW[c] = w; Identifier tex = null;
                    if (c == 1) tex = ICON_PLANETS; if (c == 2) tex = ICON_MOONS; if (c == 3) tex = ICON_STARS; if (c == 4) tex = ICON_HOLES;
                    if (tex != null) {
                        int iconPx = catX + (w - catSize)/2;
                        if (currentCategory == c) RenderSystem.setShaderColor(0.35f, 0.35f, 0.35f, 1.0f);
                        drawIcon(context, tex, iconPx, catY, catSize, catSize); RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                    }
                    catX += w;
                }
                if (c < 4) { catX += 2; context.fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3; }
            }

            for (int i = 0; i < planetHitboxes.length; i++) planetHitboxes[i][0] = -1;

            java.util.List<Integer> visibleItems = new java.util.ArrayList<>();
            for (int i = 0; i < menuItems.length; i++) {
                boolean match = false;
                if (currentCategory == 0) match = true; else if (currentCategory == 1 && menuItems[i].type.equals("Planet")) match = true; else if (currentCategory == 2 && menuItems[i].type.equals("Satellite")) match = true; else if (currentCategory == 3 && menuItems[i].type.equals("Star")) match = true; else if (currentCategory == 4 && menuItems[i].type.equals("Hole")) match = true;
                if (match) visibleItems.add(i);
            }

            boolean noPlanets = planets.isEmpty(); int sepX = width - 140;
            int iconSize = 24, startX = 10, startY = menuY + 35, spacingX = 16, spacingY = 40;
            int gridWidth = sepX - startX - 5, columns = Math.max(1, gridWidth / (iconSize + spacingX)), totalRows = (visibleItems.size() + columns - 1) / columns;
            int maxScroll = Math.max(0, totalRows * spacingY - (menuHeight - 35));
            if (menuScrollY > maxScroll) menuScrollY = maxScroll; if (menuScrollY < 0) menuScrollY = 0;

            context.enableScissor(0, startY - 5, sepX, rectY);
            for (int vi = 0; vi < visibleItems.size(); vi++) {
                int i = visibleItems.get(vi), col = vi % columns, row = vi / columns;
                int eX = startX + col * (iconSize + spacingX), eY = startY + row * spacingY - (int)menuScrollY;

                RenderSystem.setShaderTexture(0, menuItems[i].guiTex); RenderSystem.setShader(GameRenderer::getPositionTexProgram);
                Tessellator tessellator = Tessellator.getInstance(); BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
                Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
                buffer.vertex(matrix, eX, eY, 0).texture(0f, 0f); buffer.vertex(matrix, eX, eY + iconSize, 0).texture(0f, 1f); buffer.vertex(matrix, eX + iconSize, eY + iconSize, 0).texture(1f, 1f); buffer.vertex(matrix, eX + iconSize, eY, 0).texture(1f, 0f);
                BufferRenderer.drawWithGlobalProgram(buffer.end());

                String planetName = menuItems[i].name;
                context.getMatrices().push();

                float fScale = 0.5f;
                context.getMatrices().scale(fScale, fScale, 1.0f);
                int tw = client.textRenderer.getWidth(planetName), tX = (int)((eX + iconSize / 2.0f) / fScale) - tw / 2, tY = (int)((eY + iconSize + 4) / fScale);

                if (i == selectedPlanetIndex) { context.fill(tX - 4, tY - 2, tX + tw + 4, tY + client.textRenderer.fontHeight + 1, 0xAA000000); context.drawText(client.textRenderer, planetName, tX, tY, 0xFF55FF55, false); }
                else context.drawText(client.textRenderer, planetName, tX, tY, 0xFFAAAAAA, false);
                context.getMatrices().pop();

                planetHitboxes[i][0] = eX; planetHitboxes[i][1] = eY; planetHitboxes[i][2] = iconSize; planetHitboxes[i][3] = iconSize + 15;
            }
            context.disableScissor();

            context.fill(sepX, menuY + 10, sepX + 1, rectY - 10, 0xFF2A2A2A);
            int btnS = 20, gap = 8, rightStartX = sepX + 10, btnY = menuY + 20;

            staticBtnX = rightStartX; staticBtnY = btnY; staticBtnW = btnS; staticBtnH = btnS;
            drawTinyModeButton(context, client.textRenderer, "S", rightStartX, btnY, btnS, currentSpawnMode == SpawnMode.STATIC, false);
            int nextX = rightStartX + btnS + gap; orbitBtnX = nextX; orbitBtnY = btnY; orbitBtnW = btnS; orbitBtnH = btnS;
            drawTinyModeButton(context, client.textRenderer, "O", nextX, btnY, btnS, currentSpawnMode == SpawnMode.ORBIT, noPlanets);
            nextX += btnS + gap; launchBtnX = nextX; launchBtnY = btnY; launchBtnW = btnS; launchBtnH = btnS;
            drawTinyModeButton(context, client.textRenderer, "L", nextX, btnY, btnS, currentSpawnMode == SpawnMode.LAUNCH, noPlanets);

            if ((currentSpawnMode == SpawnMode.LAUNCH || currentSpawnMode == SpawnMode.ORBIT) && !noPlanets) {
                speedBoxHitX = rightStartX - 5; speedBoxHitY = btnY + btnS + 6; speedBoxHitW = 65; speedBoxHitH = 14;
                unitBoxHitX = speedBoxHitX + speedBoxHitW + 2; unitBoxHitY = speedBoxHitY; unitBoxHitW = 35; unitBoxHitH = 14;

                boolean speedBlocked = (currentSpawnMode == SpawnMode.ORBIT && autoOrbitSpeed);
                int outC = speedBlocked ? 0xFF444444 : (isTypingSpeed ? 0xFF55FF55 : 0xFF555555), txtC = speedBlocked ? 0xFF888888 : 0xFFFFFFFF;

                context.fill(speedBoxHitX, speedBoxHitY, speedBoxHitX + speedBoxHitW, speedBoxHitY + speedBoxHitH, 0xFF111111);
                drawOutline(context, speedBoxHitX, speedBoxHitY, speedBoxHitW, speedBoxHitH, outC);
                String dispSpeed = isTypingSpeed ? typedSpeed + "_" : String.format(Locale.US, "%.1f", inputSpeed);
                while (client.textRenderer.getWidth(dispSpeed) > speedBoxHitW - 4 && dispSpeed.length() > 1) dispSpeed = dispSpeed.substring(1);
                context.drawText(client.textRenderer, dispSpeed, speedBoxHitX + 3, speedBoxHitY + 3, txtC, false);

                context.fill(unitBoxHitX, unitBoxHitY, unitBoxHitX + unitBoxHitW, unitBoxHitY + unitBoxHitH, 0xFF222222);
                drawOutline(context, unitBoxHitX, unitBoxHitY, unitBoxHitW, unitBoxHitH, speedBlocked ? 0xFF444444 : 0xFF555555);
                String[] unitNames = {"c", "km/s", "km/h", "m/s"};
                context.drawText(client.textRenderer, unitNames[speedUnitIndex], unitBoxHitX + 3, unitBoxHitY + 3, speedBlocked ? 0xFF555555 : 0xFFAAAAAA, false);

                if (isUnitMenuOpen && !speedBlocked) {
                    unitMenuHitW = unitBoxHitW; unitMenuHitH = 14 * 4; unitMenuHitX = unitBoxHitX; unitMenuHitY = unitBoxHitY - unitMenuHitH;
                    context.fill(unitMenuHitX, unitMenuHitY, unitMenuHitX + unitMenuHitW, unitMenuHitY + unitMenuHitH, 0xFA060606); drawOutline(context, unitMenuHitX, unitMenuHitY, unitMenuHitW, unitMenuHitH, 0xFF777777);
                    double mX = client.mouse.getX() * width / (double) client.getWindow().getWidth(), mY = client.mouse.getY() * height / (double) client.getWindow().getHeight();
                    for (int i = 0; i < 4; i++) {
                        int itemY = unitMenuHitY + i * 14;
                        if (mX >= unitMenuHitX && mX <= unitMenuHitX + unitMenuHitW && mY >= itemY && mY < itemY + 14) context.fill(unitMenuHitX, itemY, unitMenuHitX + unitMenuHitW, itemY + 14, 0xFF333333);
                        context.drawText(client.textRenderer, unitNames[i], unitMenuHitX + 3, itemY + 3, 0xFFFFFFFF, false);
                    }
                }

                if (currentSpawnMode == SpawnMode.ORBIT) {
                    autoToggleHitW = 20; autoToggleHitH = 10; autoToggleHitX = speedBoxHitX; autoToggleHitY = speedBoxHitY + speedBoxHitH + 4;
                    int toggleColor = autoOrbitSpeed ? 0xFF225522 : 0xFF552222, toggleOut = autoOrbitSpeed ? 0xFF55FF55 : 0xFFFF5555;
                    context.fill(autoToggleHitX, autoToggleHitY, autoToggleHitX + autoToggleHitW, autoToggleHitY + autoToggleHitH, toggleColor); drawOutline(context, autoToggleHitX, autoToggleHitY, autoToggleHitW, autoToggleHitH, toggleOut);
                    context.drawText(client.textRenderer, "Auto", autoToggleHitX + 24, autoToggleHitY + 1, 0xFFAAAAAA, false);
                }
            }
        }

        context.fill(0, rectY, width, height, 0xFA060606);
        context.fill(0, rectY, width, rectY + 1, 0xFF2A2A2A);
        if (isPaused) context.fill(0, rectY - 2, width, rectY, 0xFFFFFFFF);

        int centerY = rectY + barHeight / 2;
        int playIconPadX = 6, playIconPadY = 2; String playIcon = isPaused ? "||" : ">";

        int playBtnX = 10, btnH = client.textRenderer.fontHeight + playIconPadY * 2, playBtnY = centerY - btnH / 2;
        int playBtnW = drawButton(context, client.textRenderer, playIcon, playBtnX, playBtnY, playIconPadX, playIconPadY, isPaused);
        playHitX = playBtnX; playHitY = playBtnY; playHitW = playBtnW; playHitH = btnH;

        int arrowsX = playBtnX + playBtnW + 8;
        int arrowPadX = 2, arrowPadY = 2, arrowBtnH = client.textRenderer.fontHeight + arrowPadY * 2, arrowBtnY = centerY - arrowBtnH / 2;

        int leftArrowW = drawButton(context, client.textRenderer, "<", arrowsX, arrowBtnY, arrowPadX, arrowPadY, downPressed);
        leftArrowHitX = arrowsX; leftArrowHitY = arrowBtnY; leftArrowHitW = leftArrowW; leftArrowHitH = arrowBtnH;

        int rightArrowX = arrowsX + leftArrowW + 1;
        int rightArrowW = drawButton(context, client.textRenderer, ">", rightArrowX, arrowBtnY, arrowPadX, arrowPadY, upPressed);
        rightArrowHitX = rightArrowX; rightArrowHitY = arrowBtnY; rightArrowHitW = rightArrowW; rightArrowHitH = arrowBtnH;

        LocalDateTime baseDate = LocalDateTime.of(2026, 1, 1, 12, 0);
        String timeText;
        try {
            LocalDateTime currentDate = baseDate.plusSeconds((long) simTimeSeconds);
            int year = currentDate.getYear();
            if (year > 9999 || year < -9999) {
                timeText = "Year " + formatCompact(year);
            } else {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");
                timeText = currentDate.format(formatter);
            }
        } catch (Exception e) {
            double yrsPassed = simTimeSeconds / 31536000.0;
            timeText = "Year " + formatCompact(2026 + yrsPassed);
        }

        String scaleText = formatTimeScale(timeScale);

        int timeX = rightArrowX + rightArrowW + 12;
        int textY = centerY - client.textRenderer.fontHeight / 2;
        context.drawText(client.textRenderer, timeText, timeX, textY, 0xFFFFFFFF, false);

        int sepX = timeX + client.textRenderer.getWidth(timeText) + 8;
        context.fill(sepX, centerY - 6, sepX + 1, centerY + 6, 0xFF555555);

        int scaleX = sepX + 8;
        context.drawText(client.textRenderer, scaleText, scaleX, textY, 0xFFAAAAAA, false);

        String[] rightButtons = {"Add", "Tools"}; int btnPadX = 6, gap = 8, totalRightWidth = 0;
        for (String btn : rightButtons) totalRightWidth += client.textRenderer.getWidth(btn) + btnPadX * 2 + gap;
        int currX = width - totalRightWidth - 10, rightBtnH = client.textRenderer.fontHeight + 4, rightBtnY = centerY - rightBtnH / 2;

        for (String btn : rightButtons) {
            boolean highlight = (btn.equals("Add") && isAddMenuOpen) || (btn.equals("Tools") && isToolsMenuOpen);
            int w = drawButton(context, client.textRenderer, btn, currX, rightBtnY, btnPadX, 2, highlight);
            if (btn.equals("Add")) { addBtnX = currX; addBtnY = rightBtnY; addBtnW = w; addBtnH = rightBtnH; }
            if (btn.equals("Tools")) { toolsBtnX = currX; toolsBtnY = rightBtnY; toolsBtnW = w; toolsBtnH = rightBtnH; }
            currX += w + gap;
        }

        if (lineStart3D != null && lineEnd3D != null && textMidPoint3D != null && !projectionText.isEmpty() && !isWelcomeMenuOpen) {
            Matrix4f proj = new Matrix4f().setPerspective((float) Math.toRadians(70), (float) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight(), 0.1f, 1000000f);
            Matrix4f view = new Matrix4f().rotateX((float) Math.toRadians(CameraManager.rot.x)).rotateY((float) Math.toRadians(CameraManager.rot.y));

            Vector4f startClip = new Vector4f((float)(lineStart3D.x - CameraManager.pos.x), (float)(lineStart3D.y - CameraManager.pos.y), (float)(lineStart3D.z - CameraManager.pos.z), 1.0f); view.transform(startClip); proj.transform(startClip);
            Vector4f endClip = new Vector4f((float)(lineEnd3D.x - CameraManager.pos.x), (float)(lineEnd3D.y - CameraManager.pos.y), (float)(lineEnd3D.z - CameraManager.pos.z), 1.0f); view.transform(endClip); proj.transform(endClip);
            Vector4f midClip = new Vector4f((float)(textMidPoint3D.x - CameraManager.pos.x), (float)(textMidPoint3D.y - CameraManager.pos.y), (float)(textMidPoint3D.z - CameraManager.pos.z), 1.0f); view.transform(midClip); proj.transform(midClip);

            if (startClip.w > 0.1f && endClip.w > 0.1f && midClip.w > 0.1f) {
                float sX = ((startClip.x / startClip.w) + 1.0f) / 2.0f * width, sY = ((1.0f - (startClip.y / startClip.w)) / 2.0f) * height;
                float eX = ((endClip.x / endClip.w) + 1.0f) / 2.0f * width, eY = ((1.0f - (endClip.y / endClip.w)) / 2.0f) * height;
                float midX = ((midClip.x / midClip.w) + 1.0f) / 2.0f * width, midY = ((1.0f - (midClip.y / midClip.w)) / 2.0f) * height;

                float angle = (float) Math.atan2(eY - sY, eX - sX); if (angle > Math.PI / 2 || angle < -Math.PI / 2) angle += Math.PI;
                float dynScale = Math.max(0.4f, Math.min(80.0f / midClip.w, 2.5f));

                context.getMatrices().push(); context.getMatrices().translate(midX, midY, 0); context.getMatrices().multiply(new Quaternionf().rotationZ(angle)); context.getMatrices().scale(dynScale, dynScale, 1.0f);
                int tw = client.textRenderer.getWidth(projectionText), th = client.textRenderer.fontHeight;
                context.drawText(client.textRenderer, projectionText, -tw / 2, -th - 2, 0xFFFFFFFF, true);
                context.getMatrices().pop();
            }
        }

        RenderSystem.disableDepthTest();
        for (int i = 0; i < notifications.size(); i++) {
            Notification n = notifications.get(i);
            n.targetY = 10 + i * 20;
            n.currentY += (n.targetY - n.currentY) * Math.min(1.0, dtUI * 15.0);
            n.life -= dtUI;

            if (i >= 3 || n.life <= 0) {
                n.alpha -= dtUI * 4.0;
            } else {
                n.alpha += dtUI * 5.0;
            }
            if (n.alpha > 1.0) n.alpha = 1.0;

            if (n.alpha <= 0) {
                notifications.remove(i);
                i--;
            } else {
                int textW = client.textRenderer.getWidth(n.text);
                int notifW = textW + 16;
                int notifH = 16;
                int x = width - notifW - 10;
                int y = (int) Math.round(n.currentY);
                int aInt = (int)(n.alpha * 255);
                if (aInt > 5) {
                    context.fill(x, y, x + notifW, y + notifH, (aInt << 24) | 0x111111);
                    drawOutline(context, x, y, notifW, notifH, (aInt << 24) | 0x555555);
                    context.fill(x + notifW - 2, y, x + notifW, y + notifH, (aInt << 24) | 0x55FF55);
                    context.drawText(client.textRenderer, n.text, x + 6, y + 4, (aInt << 24) | 0xFFFFFF, false);
                }
            }
        }

        if (!CameraManager.isRotating() && !isWelcomeMenuOpen) {
            double mX = client.mouse.getX() * width / (double) client.getWindow().getWidth(), mY = client.mouse.getY() * height / (double) client.getWindow().getHeight();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) {
                int aimSize = 24; drawIcon(context, CURSOR_AIM_TEX, (int)mX - aimSize / 2, (int)mY - aimSize / 2, aimSize, aimSize);
            } else {
                int cursorSize = 24; drawIcon(context, CURSOR_TEX, (int)mX, (int)mY, cursorSize, cursorSize);
            }
            RenderSystem.enableDepthTest();
        }

        context.getMatrices().pop();
    }

    public static void drawIcon(DrawContext context, Identifier tex, int x, int y, int w, int h) {
        RenderSystem.setShaderTexture(0, tex); RenderSystem.setShader(GameRenderer::getPositionTexProgram); Tessellator tessellator = Tessellator.getInstance(); BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE); Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        buffer.vertex(matrix, x, y, 0).texture(0f, 0f); buffer.vertex(matrix, x, y + h, 0).texture(0f, 1f); buffer.vertex(matrix, x + w, y + h, 0).texture(1f, 1f); buffer.vertex(matrix, x + w, y, 0).texture(1f, 0f); BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    public static int drawButton(DrawContext context, TextRenderer textRenderer, String text, int x, int y, int padX, int padY, boolean isHighlight) {
        int textW = textRenderer.getWidth(text), btnW = textW + padX * 2, btnH = textRenderer.fontHeight + padY * 2;
        context.fill(x, y, x + btnW, y + btnH, isHighlight ? 0xFF111111 : 0xFF000000); drawOutline(context, x, y, btnW, btnH, isHighlight ? 0xFFFFFFFF : 0x80FFFFFF); context.drawText(textRenderer, text, x + padX, y + padY, 0xFFFFFFFF, false); return btnW;
    }

    public static void drawMenuToggle(DrawContext context, TextRenderer tr, String text, int x, int y, int w, int h, boolean isOn) {
        context.fill(x, y, x + w, y + h, isOn ? 0xFF153315 : 0xFF111111); drawOutline(context, x, y, w, h, isOn ? 0xFF55FF55 : 0xFF444444); context.drawText(tr, text, x + 5, y + (h - tr.fontHeight) / 2 + 1, 0xFFFFFFFF, false); String state = isOn ? "ON" : "OFF"; int stateW = tr.getWidth(state); context.drawText(tr, state, x + w - stateW - 5, y + (h - tr.fontHeight) / 2 + 1, isOn ? 0xFF55FF55 : 0xFFAAAAAA, false);
    }

    public static void drawMenuAction(DrawContext context, TextRenderer tr, String text, int x, int y, int w, int h, int textColor) {
        context.fill(x, y, x + w, y + h, 0xFF220808); drawOutline(context, x, y, w, h, 0xFFFF5555); context.drawText(tr, text, x + (w - tr.getWidth(text)) / 2, y + (h - tr.fontHeight) / 2 + 1, textColor, false);
    }

    public static void drawTinyModeButton(DrawContext context, TextRenderer tr, String letter, int x, int y, int size, boolean active, boolean disabled) {
        int bgColor = disabled ? 0xFF111111 : (active ? 0xFF333333 : 0xFF080808), outlineColor = disabled ? 0xFF2A2A2A : (active ? 0xFFFFFFFF : 0xFF555555), textColor = disabled ? 0xFF555555 : (active ? 0xFFFFFFAA : 0xFFAAAAAA);
        context.fill(x, y, x + size, y + size, bgColor); drawOutline(context, x, y, size, size, outlineColor); int tx = x + (size - tr.getWidth(letter)) / 2, ty = y + (size - tr.fontHeight) / 2 + 1; context.drawText(tr, letter, tx, ty, textColor, false);
    }

    public static void drawOutline(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color); context.fill(x, y + height - 1, x + width, y + height, color); context.fill(x, y, x + 1, y + height, color); context.fill(x + width - 1, y, x + width, y + height, color);
    }

    public static void drawSunGlow(Planet p, Matrix4f rotViewMatrix, Matrix4f projMatrix) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();

        Vector4f posClip = new Vector4f((float)(p.pos.x - CameraManager.pos.x), (float)(p.pos.y - CameraManager.pos.y), (float)(p.pos.z - CameraManager.pos.z), 1.0f);
        rotViewMatrix.transform(posClip);

        float zView = posClip.z;
        if (zView >= 0) return;

        projMatrix.transform(posClip);
        if (posClip.w < 0.1f) return;

        float ndcX = posClip.x / posClip.w;
        float ndcY = posClip.y / posClip.w;

        // Координаты центра свечения на экране
        float sX = (ndcX + 1.0f) / 2.0f * width;
        float sY = (1.0f - ndcY) / 2.0f * height;

        float fovScale = projMatrix.m11();
        float screenRadius = (float)(p.radius * 6.0 / -zView) * fovScale * height * 0.5f;

        if (screenRadius < 1.0f) return;

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(770, 1);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, -1000.0F, 1000.0F);
        RenderSystem.setProjectionMatrix(ortho, VertexSorter.BY_Z);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        Matrix4f mat = new Matrix4f().translate(sX, sY, 0);

        int r = 255, g = 255, b = 255;
        if (p.temperature > 0) {
            if (p.temperature <= 3500) { r = 255; g = 100; b = 30; }
            else if (p.temperature <= 4500) { r = 255; g = 180; b = 107; }
            else if (p.temperature <= 6000) { r = 255; g = 245; b = 230; }
            else if (p.temperature <= 10000) { r = 200; g = 220; b = 255; }
            else { r = 150; g = 180; b = 255; }
        }

        int segments = 64;
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2.0 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2.0 / segments);
            buffer.vertex(mat, 0, 0, 0).color(r, g, b, 200);
            buffer.vertex(mat, (float)Math.cos(a1)*screenRadius, (float)Math.sin(a1)*screenRadius, 0).color(r, g, b, 0);
            buffer.vertex(mat, (float)Math.cos(a2)*screenRadius, (float)Math.sin(a2)*screenRadius, 0).color(r, g, b, 0);
        }

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.setProjectionMatrix(projMatrix, VertexSorter.BY_DISTANCE);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
    }

    public static void drawSkyFace(Tessellator tessellator, Matrix4f matrix, Identifier texture, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram); RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, x1, y1, z1).texture(0.0f, 0.0f); buffer.vertex(matrix, x2, y2, z2).texture(0.0f, 1.0f); buffer.vertex(matrix, x3, y3, z3).texture(1.0f, 1.0f); buffer.vertex(matrix, x4, y4, z4).texture(1.0f, 0.0f);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }
}