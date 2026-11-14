package com.viris.pack;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The uniforms Voxy gives shader packs under Iris (vxModelView, vxProj, vxViewProj with inverse and
 * previous frame, vxNearPlane, vxFarPlane, vxRenderDistance), as Vitrail uniforms. Projections are turned
 * from Voxy's reverse-Z into the depth convention packs are written for, as Voxy does.
 */
public final class VoxyPackUniforms {

    private static final Matrix4f modelView = new Matrix4f();
    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final Matrix4f previousModelView = new Matrix4f();
    private static final Matrix4f previousProjection = new Matrix4f();
    private static final Matrix4f previousViewProjection = new Matrix4f();
    private static float nearPlane = 16.0F;
    private static float farPlane = 3000.0F;
    private static long frames;

    private VoxyPackUniforms() {
    }

    /** Called once per frame with the viewport Voxy draws with. */
    public static void update(Viewport<?> viewport) {
        frames++;
        previousModelView.set(modelView);
        previousProjection.set(projection);
        previousViewProjection.set(viewProjection);
        modelView.set(viewport.modelView);
        boolean reverse = viewport.properties.isReverseZ();
        boolean zeroToOne = viewport.properties.isZero2One();
        unreverse(projection.set(viewport.projection), reverse, zeroToOne);
        unreverse(viewProjection.set(viewport.MVP), reverse, zeroToOne);
        nearPlane = viewport.nearPlane;
        farPlane = viewport.farPlane;
    }

    /** Frames Voxy drew so far (one image set each). */
    public static long frames() {
        return frames;
    }

    private static void unreverse(Matrix4f matrix, boolean reverse, boolean zeroToOne) {
        if (!reverse) {
            return;
        }
        Vector4f row = matrix.getRow(2, new Vector4f());
        if (zeroToOne) {
            row.mul(-2.0F).add(matrix.getRow(3, new Vector4f()));
        } else {
            row.mul(-1.0F);
        }
        matrix.setRow(2, row);
    }

    /** Added to Vitrail's engine uniforms (UniformCatalogMixin). */
    public static void register(UniformCatalog.Builder builder) {
        builder.add("vxRenderDistance", UniformShape.INT,
            (world, out) -> out.set(Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32.0F)));
        builder.add("vxNearPlane", UniformShape.FLOAT, (world, out) -> out.set(nearPlane));
        builder.add("vxFarPlane", UniformShape.FLOAT, (world, out) -> out.set(farPlane));
        builder.add("vxModelView", UniformShape.MAT4, (world, out) -> out.set(modelView));
        builder.add("vxModelViewInv", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f(modelView).invert()));
        builder.add("vxModelViewPrev", UniformShape.MAT4, (world, out) -> out.set(previousModelView));
        builder.add("vxProj", UniformShape.MAT4, (world, out) -> out.set(projection));
        builder.add("vxProjInv", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f(projection).invert()));
        builder.add("vxProjPrev", UniformShape.MAT4, (world, out) -> out.set(previousProjection));
        builder.add("vxViewProj", UniformShape.MAT4, (world, out) -> out.set(viewProjection));
        builder.add("vxViewProjInv", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f(viewProjection).invert()));
        builder.add("vxViewProjPrev", UniformShape.MAT4, (world, out) -> out.set(previousViewProjection));
    }
}
