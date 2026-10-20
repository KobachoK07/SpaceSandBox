package spacesb.remake;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.List;

public class Shader {

    private static int lensProgram = -1, lensVao = -1, lensVbo = -1;
    private static int screenTex = -1, screenW = 0, screenH = 0;
    private static int planetProgram = -1, planetVao = -1, planetVbo = -1;
    private static int particleProgram = -1, particleVao = -1, particleVbo = -1;

    private static final FloatBuffer geometryBuf = BufferUtils.createFloatBuffer(3000000);
    private static final FloatBuffer matrixBuf = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer bhCtrBuf = BufferUtils.createFloatBuffer(64);
    private static final FloatBuffer bhRadBuf = BufferUtils.createFloatBuffer(32);

    public static final Identifier TEX_MINI = Identifier.of("spacesb-remake", "textures/particle/explosionsmini.png");
    public static final Identifier TEX_BIG1 = Identifier.of("spacesb-remake", "textures/particle/explosions1.png");
    public static final Identifier TEX_BIG2 = Identifier.of("spacesb-remake", "textures/particle/explosions2.png");
    public static final Identifier TEX_BIG3 = Identifier.of("spacesb-remake", "textures/particle/explosions3.png");

    private static final String PLANET_VSH =
            "#version 150\n in vec3 in_Pos;\n in vec2 in_UV;\n in vec3 in_Normal;\n out vec2 fragUV;\n out vec3 worldNormal;\n out vec3 pCenter;\n uniform mat4 u_MVP;\n uniform mat4 u_Model;\n void main() {\n fragUV = in_UV;\n worldNormal = normalize(mat3(u_Model) * in_Normal);\n pCenter = (u_Model * vec4(0.0, 0.0, 0.0, 1.0)).xyz;\n gl_Position = u_MVP * vec4(in_Pos, 1.0);\n }";

    private static final String PLANET_FSH =
            "#version 150\n in vec2 fragUV;\n in vec3 worldNormal;\n in vec3 pCenter;\n out vec4 fragColor;\n uniform sampler2D u_Tex;\n" +
                    "uniform vec4 u_Sun0; uniform vec4 u_Sun1; uniform vec4 u_Sun2; uniform vec4 u_Sun3;\n" +
                    "uniform vec4 u_Sun4; uniform vec4 u_Sun5; uniform vec4 u_Sun6; uniform vec4 u_Sun7;\n" +
                    "uniform vec4 u_Sun8; uniform vec4 u_Sun9; uniform vec4 u_Sun10; uniform vec4 u_Sun11;\n" +
                    "uniform vec4 u_Sun12; uniform vec4 u_Sun13; uniform vec4 u_Sun14; uniform vec4 u_Sun15;\n" +
                    "uniform float u_IsLightSource;\n uniform float u_IsBlackHole;\n uniform float u_NoShadows;\n uniform vec4 u_Color;\n" +
                    "uniform vec3 u_CameraPos;\n" +
                    "void processSun(vec4 sun, vec3 norm, inout float totalLight, inout float totalSpec) {\n" +
                    "  if(sun.w <= -1.5) return;\n" +
                    "  vec3 lightDir = sun.xyz - pCenter;\n" +
                    "  float distSq = dot(lightDir, lightDir);\n" +
                    "  if (distSq > 0.0001) {\n" +
                    "    float dist = sqrt(distSq);\n" +
                    "    lightDir = lightDir / dist;\n" +
                    "    float NdotL = max(dot(norm, lightDir), 0.0);\n" +
                    "    float atten = (sun.w < 0.0) ? 1.0 : (sun.w / (1.0 + 0.05 * dist + 0.002 * distSq));\n" +
                    "    totalLight += NdotL * atten;\n" +
                    "    if (NdotL > 0.0) {\n" +
                    "      totalSpec += pow(NdotL, 16.0) * atten;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "void main() {\n" +
                    "  if (u_IsBlackHole > 0.5) {\n" +
                    "    fragColor = vec4(0.0, 0.0, 0.0, u_Color.a);\n" +
                    "    return;\n" +
                    "  }\n" +
                    "  vec4 texColor = texture(u_Tex, fragUV);\n" +
                    "  if (texColor.a < 0.1) discard;\n" +
                    "  if (u_IsLightSource > 0.5 || u_NoShadows > 0.5) {\n" +
                    "    fragColor = vec4(texColor.rgb * u_Color.rgb, texColor.a * u_Color.a);\n" +
                    "  } else {\n" +
                    "    float totalLight = 0.0;\n" +
                    "    float totalSpec = 0.0;\n" +
                    "    vec3 norm = normalize(worldNormal);\n" +
                    "    processSun(u_Sun0, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun1, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun2, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun3, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun4, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun5, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun6, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun7, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun8, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun9, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun10, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun11, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun12, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun13, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun14, norm, totalLight, totalSpec);\n" +
                    "    processSun(u_Sun15, norm, totalLight, totalSpec);\n" +
                    "    if (totalLight == 0.0 && u_Sun0.w <= -1.5) {\n" +
                    "      fragColor = vec4(0.0, 0.0, 0.0, texColor.a * u_Color.a);\n" +
                    "    } else {\n" +
                    "      float ambient = 0.02;\n" +
                    "      float intensity = min(ambient + totalLight, 1.0);\n" +
                    "      vec3 finalColor = texColor.rgb * u_Color.rgb * intensity;\n" +
                    "      finalColor += vec3(totalSpec * 0.4);\n" +
                    "      fragColor = vec4(min(finalColor, vec3(1.0)), texColor.a * u_Color.a);\n" +
                    "    }\n" +
                    "  }\n" +
                    "}";

    private static final String PART_VSH =
            "#version 150\n in vec3 in_Pos;\n in vec4 in_Color;\n in vec2 in_UV;\n in float in_Life;\n out vec4 fragColor;\n out vec2 fragUV;\n out float fragLife;\n uniform mat4 u_MVP;\n void main() {\n fragColor = in_Color;\n fragUV = in_UV;\n fragLife = in_Life;\n gl_Position = u_MVP * vec4(in_Pos, 1.0);\n }";

    private static final String PART_FSH =
            "#version 150\n in vec4 fragColor;\n in vec2 fragUV;\n in float fragLife;\n out vec4 fragColorOut;\n uniform sampler2D u_Tex;\n void main() {\n" +
                    "  vec4 texColor = texture(u_Tex, fragUV);\n" +
                    "  if (texColor.a < 0.05) discard;\n" +
                    "  float outAlpha = texColor.a * fragColor.a * smoothstep(0.0, 0.9, fragLife);\n" +
                    "  if (outAlpha < 0.01) discard;\n" +
                    "  fragColorOut = vec4(texColor.rgb * fragColor.rgb, outAlpha);\n" +
                    "}";

    private static final String LENS_VSH =
            "#version 150\n in vec2 in_Pos;\n out vec2 fragCoord;\n void main() {\n fragCoord = (in_Pos + 1.0) / 2.0;\n gl_Position = vec4(in_Pos, 0.0, 1.0);\n }";

    private static final String LENS_FSH =
            "#version 150\n in vec2 fragCoord;\n out vec4 fragColor;\n uniform sampler2D u_Screen;\n" +
                    "uniform vec2 u_BHCtr[32];\n" +
                    "uniform float u_BHRad[32];\n" +
                    "uniform int u_BHCount;\n" +
                    "uniform float u_Aspect;\n" +
                    "void applyWarp(vec2 ctr, float rs, inout vec2 warp_uv) {\n" +
                    "  if(rs <= 0.0) return;\n" +
                    "  vec2 p = warp_uv - ctr; p.x *= u_Aspect;\n" +
                    "  float d = length(p);\n" +
                    "  if (d > rs * 0.1) {\n" +
                    "    float fade = 1.0 - smoothstep(rs * 0.5, rs * 25.0, d);\n" +
                    "    if (fade > 0.0) {\n" +
                    "      float warp = (rs * rs * 3.5) / d;\n" +
                    "      vec2 dir = p / d; dir.x /= u_Aspect;\n" +
                    "      warp_uv -= dir * warp * fade;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "void main() {\n" +
                    "  vec2 warp_uv = fragCoord;\n" +
                    "  bool insideEventHorizon = false;\n" +
                    "  for(int i=0; i<u_BHCount; i++) {\n" +
                    "    vec2 p = fragCoord - u_BHCtr[i]; p.x *= u_Aspect;\n" +
                    "    float d = length(p);\n" +
                    "    if (d < u_BHRad[i] * 0.6) { insideEventHorizon = true; }\n" +
                    "    applyWarp(u_BHCtr[i], u_BHRad[i], warp_uv);\n" +
                    "  }\n" +
                    "  warp_uv = clamp(warp_uv, 0.001, 0.999);\n" +
                    "  if (insideEventHorizon) {\n" +
                    "    fragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                    "  } else {\n" +
                    "    fragColor = texture(u_Screen, warp_uv);\n" +
                    "  }\n" +
                    "}";

    public static void init() {
        if (lensProgram != -1) return;
        try {
            int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int oldVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

            lensVao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(lensVao);
            lensVbo = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, lensVbo);
            float[] quadVertices = { -1.0f, -1.0f,  1.0f, -1.0f,  -1.0f, 1.0f,  1.0f, 1.0f };
            geometryBuf.clear(); geometryBuf.put(quadVertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometryBuf, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0); GL20.glEnableVertexAttribArray(0);

            screenTex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            lensProgram = createProgram(LENS_VSH, LENS_FSH, new String[]{"in_Pos"});

            planetVao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(planetVao);
            planetVbo = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, planetVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 36 * 8 * 4, GL15.GL_DYNAMIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 8 * 4, 0); GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 8 * 4, 3 * 4); GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, 8 * 4, 5 * 4); GL20.glEnableVertexAttribArray(2);
            planetProgram = createProgram(PLANET_VSH, PLANET_FSH, new String[]{"in_Pos", "in_UV", "in_Normal"});

            particleVao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(particleVao);
            particleVbo = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, particleVbo);
            int stride = 10 * 4;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0); GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3 * 4); GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 5 * 4); GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, stride, 9 * 4); GL20.glEnableVertexAttribArray(3);
            particleProgram = createProgram(PART_VSH, PART_FSH, new String[]{"in_Pos", "in_UV", "in_Color", "in_Life"});

            GL30.glBindVertexArray(oldVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static int createProgram(String vsh, String fsh, String[] attribs) {
        int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER); GL20.glShaderSource(vs, vsh); GL20.glCompileShader(vs);
        int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER); GL20.glShaderSource(fs, fsh); GL20.glCompileShader(fs);
        int prog = GL20.glCreateProgram(); GL20.glAttachShader(prog, vs); GL20.glAttachShader(prog, fs);
        for(int i=0; i<attribs.length; i++) GL20.glBindAttribLocation(prog, i, attribs[i]);
        GL20.glLinkProgram(prog); return prog;
    }

    private static Vector3f get3D(float cx, float cy, Vector3f fn, float R) {
        if (fn.x > 0.5f) return new Vector3f(R, cy, -cx);
        if (fn.x < -0.5f) return new Vector3f(-R, cy, cx);
        if (fn.y > 0.5f) return new Vector3f(cx, R, -cy);
        if (fn.y < -0.5f) return new Vector3f(cx, -R, cy);
        if (fn.z > 0.5f) return new Vector3f(cx, cy, R);
        if (fn.z < -0.5f) return new Vector3f(-cx, cy, -R);
        return new Vector3f(cx, cy, R);
    }

    private static void pushV(FloatBuffer buf, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        buf.put(x).put(y).put(z).put(u).put(v).put(nx).put(ny).put(nz);
    }

    private static void buildFaceBuf(FloatBuffer buf, float r, String face, float[] uv) {
        float u1 = uv[0] / 16f;
        float v1 = uv[1] / 16f;
        float u2 = uv[2] / 16f;
        float v2 = uv[3] / 16f;

        float x1=0, y1=0, z1=0, x2=0, y2=0, z2=0, x3=0, y3=0, z3=0, x4=0, y4=0, z4=0;
        float nx=0, ny=0, nz=0;

        if (face.equals("south")) {
            x1=-r; y1=-r; z1= r;
            x2= r; y2=-r; z2= r;
            x3= r; y3= r; z3= r;
            x4=-r; y4= r; z4= r;
            nx=0; ny=0; nz=1;
        } else if (face.equals("north")) {
            x1= r; y1=-r; z1=-r;
            x2=-r; y2=-r; z2=-r;
            x3=-r; y3= r; z3=-r;
            x4= r; y4= r; z4=-r;
            nx=0; ny=0; nz=-1;
        } else if (face.equals("west")) {
            x1=-r; y1=-r; z1=-r;
            x2=-r; y2=-r; z2= r;
            x3=-r; y3= r; z3= r;
            x4=-r; y4= r; z4=-r;
            nx=-1; ny=0; nz=0;
        } else if (face.equals("east")) {
            x1= r; y1=-r; z1= r;
            x2= r; y2=-r; z2=-r;
            x3= r; y3= r; z3=-r;
            x4= r; y4= r; z4= r;
            nx=1; ny=0; nz=0;
        } else if (face.equals("up")) {
            x1=-r; y1= r; z1= r;
            x2= r; y2= r; z2= r;
            x3= r; y3= r; z3=-r;
            x4=-r; y4= r; z4=-r;
            nx=0; ny=1; nz=0;
        } else if (face.equals("down")) {
            x1=-r; y1=-r; z1=-r;
            x2= r; y2=-r; z2=-r;
            x3= r; y3=-r; z3= r;
            x4=-r; y4=-r; z4= r;
            nx=0; ny=-1; nz=0;
        }

        pushV(buf, x1, y1, z1, u1, v2, nx, ny, nz);
        pushV(buf, x2, y2, z2, u2, v2, nx, ny, nz);
        pushV(buf, x3, y3, z3, u2, v1, nx, ny, nz);

        pushV(buf, x1, y1, z1, u1, v2, nx, ny, nz);
        pushV(buf, x3, y3, z3, u2, v1, nx, ny, nz);
        pushV(buf, x4, y4, z4, u1, v1, nx, ny, nz);
    }

    public static void renderScorches(Matrix4f fullViewMatrix, Matrix4f projMatrix, List<SpaceEngine.Planet> planets) {
        init();
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        GL20.glUseProgram(particleProgram);

        float[] mvpArr = new float[16]; new Matrix4f(projMatrix).mul(fullViewMatrix).get(mvpArr);
        matrixBuf.clear(); matrixBuf.put(mvpArr).flip();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(particleProgram, "u_MVP"), false, matrixBuf);
        GL20.glUniform1i(GL20.glGetUniformLocation(particleProgram, "u_Tex"), 0);

        RenderSystem.enableBlend(); RenderSystem.blendFunc(770, 1); RenderSystem.depthMask(false); RenderSystem.disableCull();
        GL30.glBindVertexArray(particleVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, particleVbo);
        RenderSystem.setShaderTexture(0, TEX_BIG2); AbstractTexture mcTex = MinecraftClient.getInstance().getTextureManager().getTexture(TEX_BIG2); if (mcTex != null) GL11.glBindTexture(GL11.GL_TEXTURE_2D, mcTex.getGlId());

        int count = 0; for (SpaceEngine.Planet p : planets) count += p.scorches.size();
        if (count > 0) {
            geometryBuf.clear();
            int drawn = 0;
            Vector3f[] faceNormals = { new Vector3f(1,0,0), new Vector3f(-1,0,0), new Vector3f(0,1,0), new Vector3f(0,-1,0), new Vector3f(0,0,1), new Vector3f(0,0,-1) };

            Vector3d camPos = CameraManager.pos;

            for (SpaceEngine.Planet p : planets) {
                if (p.scorches.isEmpty()) continue;
                float f = 0.0f;
                if (!p.type.equals("Hole") && p.mass > 0 && p.rotationPeriod > 0.001) {
                    double rotP = Math.max(0.1, p.rotationPeriod);
                    f = (float) (0.011 * (p.radius * p.radius * p.radius) / (p.mass * rotP * rotP));
                    if (f > 0.15f) f = 0.15f;
                }

                float rotY = 0.0f;
                if (p.rotationPeriod > 0.001) {
                    rotY = (float) (((SpaceEngine.simTimeSeconds / 3600.0) % p.rotationPeriod) / p.rotationPeriod * 360.0);
                }
                Matrix4f rotOnly = new Matrix4f().rotateZ((float)Math.toRadians(p.tilt)).rotateY((float)Math.toRadians(-rotY));

                for (SpaceEngine.Scorch s : p.scorches) {
                    if (drawn * 6 >= 90000) break;
                    float lifeRatio = (float)(s.life / s.maxLife); if (lifeRatio < 0) lifeRatio = 0;
                    float R = (float)p.radius;
                    float maxComp = (float)Math.max(Math.abs(s.localNorm.x), Math.max(Math.abs(s.localNorm.y), Math.abs(s.localNorm.z)));
                    if (maxComp < 0.001f) maxComp = 1.0f;
                    Vector3f C = new Vector3f((float)s.localNorm.x, (float)s.localNorm.y, (float)s.localNorm.z).div(maxComp).mul(R);
                    float S = (float)(s.size * 1.5);

                    for (Vector3f FN : faceNormals) {
                        float dist = R - C.dot(FN);
                        if (dist > S || dist < -S) continue;
                        float cx = 0, cy = 0;
                        if (FN.x > 0.5f) { cx = -C.z; cy = C.y; } else if (FN.x < -0.5f) { cx = C.z; cy = C.y; } else if (FN.y > 0.5f) { cx = C.x; cy = -C.z; } else if (FN.y < -0.5f) { cx = C.x; cy = C.z; } else if (FN.z > 0.5f) { cx = C.x; cy = C.y; } else if (FN.z < -0.5f) { cx = -C.x; cy = C.y; }

                        float minX = cx - S; float maxX = cx + S; float minY = cy - S; float maxY = cy + S;
                        float cMinX = Math.max(-R, Math.min(R, minX)); float cMaxX = Math.max(-R, Math.min(R, maxX)); float cMinY = Math.max(-R, Math.min(R, minY)); float cMaxY = Math.max(-R, Math.min(R, maxY));
                        if (cMinX >= cMaxX || cMinY >= cMaxY) continue;

                        float tex_u_min = (cMinX - minX) / (2 * S); float tex_u_max = (cMaxX - minX) / (2 * S); float tex_v_min = (cMinY - minY) / (2 * S); float tex_v_max = (cMaxY - minY) / (2 * S);

                        Vector3f v00 = get3D(cMinX, cMinY, FN, R); Vector3f v10 = get3D(cMaxX, cMinY, FN, R); Vector3f v11 = get3D(cMaxX, cMaxY, FN, R); Vector3f v01 = get3D(cMinX, cMaxY, FN, R);

                        Vector3f worldNorm = new Vector3f(FN); rotOnly.transformDirection(worldNorm); worldNorm.normalize();
                        Vector3f offset = new Vector3f(worldNorm).mul(R * 0.015f);

                        Vector3f[] quad = {v00, v10, v11, v01};
                        for (Vector3f v : quad) {
                            v.x *= (1.0f + f * 0.5f); v.y *= (1.0f - f); v.z *= (1.0f + f * 0.5f);
                            rotOnly.transformPosition(v);
                            // FLOATING ORIGIN ДЛЯ ДЕКАЛЕЙ
                            v.add((float)(p.pos.x - camPos.x), (float)(p.pos.y - camPos.y), (float)(p.pos.z - camPos.z)).add(offset);
                        }

                        float alpha = lifeRatio * 1.5f; if (alpha > 1.0f) alpha = 1.0f;

                        geometryBuf.put(v00.x).put(v00.y).put(v00.z).put(tex_u_min).put(1f-tex_v_min).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        geometryBuf.put(v10.x).put(v10.y).put(v10.z).put(tex_u_max).put(1f-tex_v_min).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        geometryBuf.put(v11.x).put(v11.y).put(v11.z).put(tex_u_max).put(1f-tex_v_max).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        geometryBuf.put(v00.x).put(v00.y).put(v00.z).put(tex_u_min).put(1f-tex_v_min).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        geometryBuf.put(v11.x).put(v11.y).put(v11.z).put(tex_u_max).put(1f-tex_v_max).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        geometryBuf.put(v01.x).put(v01.y).put(v01.z).put(tex_u_min).put(1f-tex_v_max).put(1f).put(1f).put(1f).put(alpha).put(lifeRatio);
                        drawn += 6;
                    }
                }
            }
            if (drawn > 0) {
                geometryBuf.flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometryBuf, GL15.GL_DYNAMIC_DRAW);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, drawn);
            }
        }

        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL20.glUseProgram(prevProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.defaultBlendFunc();
    }

    public static void renderParticles(Matrix4f mvp, List<SpaceEngine.Particle> particles, Vector3f camRight, Vector3f camUp) {
        if (particles.isEmpty()) return;
        init();
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        GL20.glUseProgram(particleProgram);
        float[] mvpArr = new float[16]; mvp.get(mvpArr);
        matrixBuf.clear(); matrixBuf.put(mvpArr).flip();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(particleProgram, "u_MVP"), false, matrixBuf);
        GL20.glUniform1i(GL20.glGetUniformLocation(particleProgram, "u_Tex"), 0);

        RenderSystem.enableBlend(); RenderSystem.blendFunc(770, 1); RenderSystem.depthMask(false); RenderSystem.disableCull();
        GL30.glBindVertexArray(particleVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, particleVbo);

        drawParticlePass(particles, 0, TEX_MINI, camRight, camUp);
        drawParticlePass(particles, 1, TEX_BIG1, camRight, camUp);
        drawParticlePass(particles, 3, TEX_BIG3, camRight, camUp);

        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL20.glUseProgram(prevProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
    }

    private static void drawParticlePass(List<SpaceEngine.Particle> particles, int typeToDraw, Identifier tex, Vector3f camRight, Vector3f camUp) {
        RenderSystem.setShaderTexture(0, tex);
        AbstractTexture mcTex = MinecraftClient.getInstance().getTextureManager().getTexture(tex);
        if (mcTex != null) GL11.glBindTexture(GL11.GL_TEXTURE_2D, mcTex.getGlId());

        int count = 0; for (SpaceEngine.Particle p : particles) if (p.type == typeToDraw) count++;
        if (count == 0) return;

        geometryBuf.clear();
        int drawn = 0;
        Vector3d camPos = CameraManager.pos;

        for (SpaceEngine.Particle p : particles) {
            if (p.type != typeToDraw) continue;
            if (drawn * 6 >= 90000) break;
            float lifeRatio = (float)(p.life / p.maxLife); if (lifeRatio < 0) lifeRatio = 0;
            float r = p.r / 255f, g = p.g / 255f, b = p.b / 255f, alpha = lifeRatio;
            float currentSize = (float)p.size * (1.0f + (1.0f - lifeRatio) * (typeToDraw == 0 ? 3.0f : 1.5f));

            float relX = (float)(p.pos.x - camPos.x);
            float relY = (float)(p.pos.y - camPos.y);
            float relZ = (float)(p.pos.z - camPos.z);

            float px1 = relX - camRight.x * currentSize - camUp.x * currentSize, py1 = relY - camRight.y * currentSize - camUp.y * currentSize, pz1 = relZ - camRight.z * currentSize - camUp.z * currentSize;
            float px2 = relX + camRight.x * currentSize - camUp.x * currentSize, py2 = relY + camRight.y * currentSize - camUp.y * currentSize, pz2 = relZ + camRight.z * currentSize - camUp.z * currentSize;
            float px3 = relX + camRight.x * currentSize + camUp.x * currentSize, py3 = relY + camRight.y * currentSize + camUp.y * currentSize, pz3 = relZ + camRight.z * currentSize + camUp.z * currentSize;
            float px4 = relX - camRight.x * currentSize + camUp.x * currentSize, py4 = relY - camRight.y * currentSize + camUp.y * currentSize, pz4 = relZ - camRight.z * currentSize + camUp.z * currentSize;

            geometryBuf.put(px1).put(py1).put(pz1).put(0f).put(0f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            geometryBuf.put(px2).put(py2).put(pz2).put(1f).put(0f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            geometryBuf.put(px3).put(py3).put(pz3).put(1f).put(1f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            geometryBuf.put(px1).put(py1).put(pz1).put(0f).put(0f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            geometryBuf.put(px3).put(py3).put(pz3).put(1f).put(1f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            geometryBuf.put(px4).put(py4).put(pz4).put(0f).put(1f).put(r).put(g).put(b).put(alpha).put(lifeRatio);
            drawn += 6;
        }

        if (drawn > 0) {
            geometryBuf.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometryBuf, GL15.GL_DYNAMIC_DRAW);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, drawn);
        }
    }

    public static void renderPlanet(Matrix4f fullViewMatrix, Matrix4f projMatrix, SpaceEngine.Planet p, float[] suns, int sunCount, Vector3d cameraPos, float rCol, float gCol, float bCol, float alpha, boolean isBlackHole, boolean isFlashlight) {
        init();
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        AbstractTexture tex = MinecraftClient.getInstance().getTextureManager().getTexture(p.texture); if (tex != null) GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.getGlId());

        GL20.glUseProgram(planetProgram);

        float[] mvpArr = new float[16]; float[] modArr = new float[16];
        Matrix4f model = new Matrix4f();

        // FLOATING ORIGIN ДЛЯ ПЛАНЕТ
        model.translate((float)(p.pos.x - cameraPos.x), (float)(p.pos.y - cameraPos.y), (float)(p.pos.z - cameraPos.z));
        model.rotateZ((float) Math.toRadians(p.tilt));

        float rotY = 0.0f;
        if (p.rotationPeriod > 0.001) {
            rotY = (float) (((SpaceEngine.simTimeSeconds / 3600.0) % p.rotationPeriod) / p.rotationPeriod * 360.0);
        }
        model.rotateY((float) Math.toRadians(-rotY));

        float f = 0.0f;
        if (!isBlackHole && p.mass > 0 && p.rotationPeriod > 0.001) {
            float rotP = (float) Math.max(0.1, p.rotationPeriod);
            f = (float) (0.011 * (p.radius * p.radius * p.radius) / (p.mass * rotP * rotP));
            if (f > 0.15f) f = 0.15f;
        }
        model.scale(1.0f + f * 0.5f, 1.0f - f, 1.0f + f * 0.5f);

        Matrix4f mvp = new Matrix4f(projMatrix).mul(fullViewMatrix).mul(model);

        mvp.get(mvpArr);
        matrixBuf.clear(); matrixBuf.put(mvpArr).flip();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(planetProgram, "u_MVP"), false, matrixBuf);

        model.get(modArr);
        matrixBuf.clear(); matrixBuf.put(modArr).flip();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(planetProgram, "u_Model"), false, matrixBuf);
        GL20.glUniform1i(GL20.glGetUniformLocation(planetProgram, "u_Tex"), 0);

        for (int i = 0; i < 16; i++) {
            int loc = GL20.glGetUniformLocation(planetProgram, "u_Sun" + i);
            if (loc != -1) GL20.glUniform4f(loc, suns[i*4], suns[i*4+1], suns[i*4+2], suns[i*4+3]);
        }

        // Камера всегда в 0,0,0, так как мы вычли её позицию из матрицы модели
        GL20.glUniform3f(GL20.glGetUniformLocation(planetProgram, "u_CameraPos"), 0, 0, 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(planetProgram, "u_IsLightSource"), p.type.equals("Star") ? 1.0f : 0.0f);
        GL20.glUniform1f(GL20.glGetUniformLocation(planetProgram, "u_IsBlackHole"), isBlackHole ? 1.0f : 0.0f);
        GL20.glUniform1f(GL20.glGetUniformLocation(planetProgram, "u_NoShadows"), isFlashlight ? 1.0f : 0.0f);
        GL20.glUniform4f(GL20.glGetUniformLocation(planetProgram, "u_Color"), rCol, gCol, bCol, alpha);

        GL30.glBindVertexArray(planetVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, planetVbo);

        geometryBuf.clear();
        float r = (float)p.radius;
        buildFaceBuf(geometryBuf, r, "north", p.uvMap[0]);
        buildFaceBuf(geometryBuf, r, "east", p.uvMap[1]);
        buildFaceBuf(geometryBuf, r, "south", p.uvMap[2]);
        buildFaceBuf(geometryBuf, r, "west", p.uvMap[3]);
        buildFaceBuf(geometryBuf, r, "up", p.uvMap[4]);
        buildFaceBuf(geometryBuf, r, "down", p.uvMap[5]);
        geometryBuf.flip();

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometryBuf, GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);

        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL20.glUseProgram(prevProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
    }

    public static void renderAllLensing(List<SpaceEngine.Planet> planets, SpaceEngine.Planet holoPlanet, Matrix4f rotViewMatrix, Matrix4f projMatrix) {
        init(); MinecraftClient client = MinecraftClient.getInstance(); int winW = client.getWindow().getFramebufferWidth(), winH = client.getWindow().getFramebufferHeight(); float aspect = (float) winW / winH;
        float[] bhCtrs = new float[64], bhRads = new float[32]; int bhCount = 0;
        for (SpaceEngine.Planet p : planets) { if (p.type.equals("Hole") && bhCount < 32) { if (processBHForLensing(p, rotViewMatrix, projMatrix, bhCtrs, bhRads, bhCount)) bhCount++; } }
        if (holoPlanet != null && holoPlanet.type.equals("Hole") && bhCount < 32) { if (processBHForLensing(holoPlanet, rotViewMatrix, projMatrix, bhCtrs, bhRads, bhCount)) bhCount++; }
        if (bhCount == 0) return;
        if (screenW != winW || screenH != winH) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTex); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, winW, winH, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null); screenW = winW; screenH = winH; }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTex); GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, winW, winH);

        GL20.glUseProgram(lensProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(lensProgram, "u_Screen"), 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(lensProgram, "u_Aspect"), aspect);

        bhCtrBuf.clear(); bhCtrBuf.put(bhCtrs).flip();
        GL20.glUniform2fv(GL20.glGetUniformLocation(lensProgram, "u_BHCtr"), bhCtrBuf);
        bhRadBuf.clear(); bhRadBuf.put(bhRads).flip();
        GL20.glUniform1fv(GL20.glGetUniformLocation(lensProgram, "u_BHRad"), bhRadBuf);
        GL20.glUniform1i(GL20.glGetUniformLocation(lensProgram, "u_BHCount"), bhCount);

        RenderSystem.disableDepthTest(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); GL30.glBindVertexArray(lensVao); GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL20.glUseProgram(prevProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.enableDepthTest();
    }

    private static boolean processBHForLensing(SpaceEngine.Planet p, Matrix4f rotViewMatrix, Matrix4f projMatrix, float[] bhCtrs, float[] bhRads, int index) {
        Vector3d camPos = CameraManager.pos;
        Vector3f toBH = new Vector3f((float)(p.pos.x - camPos.x), (float)(p.pos.y - camPos.y), (float)(p.pos.z - camPos.z));
        if (toBH.lengthSquared() < 0.01f) return false; toBH.normalize();
        float pitch = (float) Math.toRadians(CameraManager.rot.x), yaw = (float) Math.toRadians(CameraManager.rot.y);
        Vector3f forward = new Vector3f(0, 0, -1).rotateX(-pitch).rotateY(-yaw).normalize(); if (toBH.dot(forward) < 0.3f) return false;

        Vector4f viewPos = new Vector4f((float)(p.pos.x - camPos.x), (float)(p.pos.y - camPos.y), (float)(p.pos.z - camPos.z), 1.0f);
        rotViewMatrix.transform(viewPos); if (viewPos.z >= -0.1f) return false;
        Vector4f clipPos = new Vector4f(viewPos); projMatrix.transform(clipPos); if (clipPos.w <= 0.001f) return false;
        float ndcX = clipPos.x / clipPos.w, ndcY = clipPos.y / clipPos.w, screenX = (ndcX + 1.0f) / 2.0f, screenY = (ndcY + 1.0f) / 2.0f, fov = (float) Math.toRadians(70), screenRad = (float) ((p.radius / (clipPos.w * Math.tan(fov/2.0))) * 0.55);
        bhCtrs[index * 2] = screenX; bhCtrs[index * 2 + 1] = screenY; bhRads[index] = Math.min(screenRad, 2.0f); return true;
    }
}