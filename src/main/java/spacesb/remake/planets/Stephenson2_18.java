package spacesb.remake.planets;

import net.minecraft.util.Identifier;

public class Stephenson2_18 extends BasePlanet {
    private static final float[][] UV_MAP = {{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16}};

    @Override
    public String getName() { return "Stephenson 2-18"; }

    @Override
    public String getType() { return "Star"; }

    @Override
    public Identifier getGuiTex() {
        // Иконка для меню (можно использовать дефолтную для звезд)
        return Identifier.of("spacesb-remake", "textures/gui/stephenson2_18gui.png");
    }

    @Override
    public Identifier getPlanetTex() {
        // Твоя будущая текстура гипергиганта
        return Identifier.of("spacesb-remake", "textures/planet/stephenson2_18.png");
    }

    @Override
    public float getRadius() { return 1188200.0f; }

    @Override
    public double getMass() { return 4995000.0; }

    @Override
    public float getTilt() { return 0f; }

    @Override
    public double getRotationPeriod() {
        // ИСПРАВЛЕНИЕ: Огромное число, чтобы он почти не вращался и его не разорвало физикой
        return 999999999.0;
    }

    @Override
    public double getTemperature() { return 3200.0; }

    @Override
    public float[][] getUvMap() { return UV_MAP; }
}