package spacesb.remake.planets;

import net.minecraft.util.Identifier;

public class Ton618 extends BasePlanet {
    private static final float[][] UV_MAP = {{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16}};

    @Override
    public String getName() { return "TON 618"; }
    @Override
    public String getType() { return "Hole"; }
    @Override
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/blackholegui.png"); }
    @Override
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/sa.png"); }

    @Override
    public float getRadius() { return 154347000.0f; } // ~1300 А.Е.
    @Override
    public double getMass() { return 2.1978e16; } // 66 млрд масс Солнца
    @Override
    public float getTilt() { return 0f; }
    @Override
    public double getRotationPeriod() { return 0; }
    @Override
    public double getTemperature() { return 0; }
    @Override
    public float[][] getUvMap() { return UV_MAP; }
}