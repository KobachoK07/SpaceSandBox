package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class M60UDS1 extends BasePlanet {
    private static final float[][] UV_MAP = {{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16},{0,0,16,16}};
    public String getName() { return "M60-UDS1"; }
    public String getType() { return "Hole"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/blackholegui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/sa.png"); }
    public float getRadius() { return 50000.0f; }
    public double getMass() { return 6.99e12; }
    public float getTilt() { return 0f; }
    public double getRotationPeriod() { return 0; }
    public double getTemperature() { return 0; }
    public float[][] getUvMap() { return UV_MAP; }
}