package spacesb.remake.planets;

public abstract class BasePlanet {
    public static final float[][] UV_DEFAULT = {
            {0, 0, 4, 4}, {0, 4, 4, 8}, {4, 0, 8, 4},
            {4, 4, 8, 8}, {4, 12, 0, 8}, {12, 0, 8, 4}
    };

    public abstract String getName();
    public abstract String getType();
    public abstract String getGuiTex();
    public abstract String getPlanetTex();
    public abstract double getRadius();
    public abstract double getMass();
    public abstract float getTilt();
    public abstract double getRotationPeriod();
    public abstract double getTemperature();

    public boolean isRigid() { return false; }
    public float[][] getUvMap() { return UV_DEFAULT; }

    // Плоский предвычисленный и защищённый от ошибок размерности массив 24 float (6 граней * 4 координаты)
    public float[] getFlatNormalizedUv() {
        float[] flat = new float[24];
        float[][] src = getUvMap();
        if (src == null) src = UV_DEFAULT;
        for (int f = 0; f < 6; f++) {
            float[] face = (f < src.length && src[f] != null && src[f].length >= 4) ? src[f] : UV_DEFAULT[f];
            int base = f * 4;
            flat[base]     = face[0] / 16.0f;
            flat[base + 1] = face[1] / 16.0f;
            flat[base + 2] = face[2] / 16.0f;
            flat[base + 3] = face[3] / 16.0f;
        }
        return flat;
    }
}