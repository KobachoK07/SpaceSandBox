package spacesb.remake.planets;
import net.minecraft.util.Identifier;

public class Venus extends BasePlanet {
    private static final float[][] UV_MAP = {{0, 0, 4, 4},{0, 4, 4, 8},{4, 0, 8, 4},{4, 4, 8, 8},{4, 12, 0, 8},{4, 8, 0, 12}};
    public String getName() { return "Venus"; }
    public String getType() { return "Planet"; }
    public Identifier getGuiTex() { return Identifier.of("spacesb-remake", "textures/gui/venusgui.png"); }
    public Identifier getPlanetTex() { return Identifier.of("spacesb-remake", "textures/planet/venus.png"); }
    public float getRadius() { return 4.803f; }
    public double getMass() { return 0.815; }
    public float getTilt() { return 177.36f; }
    public double getRotationPeriod() { return 5832.5; }
    public double getTemperature() { return 464.0; }
    public float[][] getUvMap() { return UV_MAP; }
    public boolean isRigid() { return true; }
}