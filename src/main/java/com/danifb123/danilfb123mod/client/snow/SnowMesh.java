package com.danifb123.danilfb123mod.client.snow;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Плоская сетка из квадов, вершины хранят только индекс ячейки (x, 0, z).
 * Высоту и положение в мире считает вершинный шейдер, поэтому сетка строится один раз.
 */
public final class SnowMesh {
    private SnowMesh() {}

    /**
     * @param half половина стороны в ячейках (индексы вершин от -half до +half)
     * @param hole если > 0, ячейки внутри квадрата [-hole, hole) пропускаются (там рисуется мелкая сетка)
     */
    public static VertexBuffer build(int half, int hole) {
        long cells = (long) (2 * half) * (2 * half);
        ByteBufferBuilder bytes = new ByteBufferBuilder((int) Math.min(Integer.MAX_VALUE - 16, cells * 4 * 12 + 1024));
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        for (int i = -half; i < half; i++) {
            for (int j = -half; j < half; j++) {
                if (hole > 0 && i >= -hole && i < hole && j >= -hole && j < hole) continue;
                b.addVertex(i, 0f, j);
                b.addVertex(i, 0f, j + 1);
                b.addVertex(i + 1, 0f, j + 1);
                b.addVertex(i + 1, 0f, j);
            }
        }

        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(b.buildOrThrow());
        VertexBuffer.unbind();
        bytes.close();
        return vb;
    }
}
