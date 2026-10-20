package spacesb.remake.planets;

import net.minecraft.util.Identifier;

public class PhoenixA extends BasePlanet {
    private static final float[][] UV_MAP = {{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16}};

    @Override
    public String getName() { return "Phoenix A*"; }
    @Override
    public String getType() { return "Hole"; }
    @Override
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/blackholegui.png"); }
    @Override
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/sa.png"); }

    @Override
    public float getRadius() { return 228570000.0f; } // ~1925 А.Е.
    @Override
    public double getMass() { return 3.33e16; } // 100 млрд масс Солнца
    @Override
    public float getTilt() { return 0f; }
    @Override
    public double getRotationPeriod() { return 0; }
    @Override
    public double getTemperature() { return 0; }
    @Override
    public float[][] getUvMap() { return UV_MAP; }
}