package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Sun extends BasePlanet {
    private static final float[][] UV_MAP = {{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16}};
    public String getName() { return "Sun"; }
    public String getType() { return "Star"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/sungui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/sun.png"); }
    public float getRadius() { return 552.65f; } // 696,340 км
    public double getMass() { return 333000.0; }
    public float getTilt() { return 7.25f; }
    public double getRotationPeriod() { return 609.12; } // В ЧАСАХ (25.38 дней)
    public double getTemperature() { return 5505.0; }
    public float[][] getUvMap() { return UV_MAP; }
}