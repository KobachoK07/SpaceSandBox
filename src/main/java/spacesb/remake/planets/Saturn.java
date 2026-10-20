package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Saturn extends BasePlanet {
    private static final float[][] UV_MAP = {{4, 0, 8, 4},{0, 0, 4, 4},{4, 4, 8, 8},{0, 4, 4, 8},{4, 12, 0, 8},{0, 8, 4, 12}};
    public String getName() { return "Saturn"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/saturngui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/saturn.png"); }
    public float getRadius() { return 46.215f; }
    public double getMass() { return 95.15; }
    public float getTilt() { return 26.73f; }
    public double getRotationPeriod() { return 10.66; }
    public double getTemperature() { return -140.0; }
    public float[][] getUvMap() { return UV_MAP; }
}