package com.tunai.api.domain.model;

import com.tunai.api.domain.exception.ProviderFailureException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Modelo 3D en formato GLB (glTF binario). Inmutable: los bytes no se exponen directamente para evitar copias de
 * varios MB por petición.
 */
public final class GeneratedModel {

    private static final byte[] GLB_MAGIC = {'g', 'l', 'T', 'F'};

    private final byte[] glb;

    private GeneratedModel(byte[] glb) {
        this.glb = glb;
    }

    /**
     * Crea el modelo validando la firma GLB (RF-BE-013).
     *
     * @throws ProviderFailureException si los bytes no son un GLB (flujo A7: respuesta no-GLB del proveedor)
     */
    public static GeneratedModel fromGlb(byte[] glb) {
        Objects.requireNonNull(glb, "glb");
        if (!hasGlbMagic(glb)) {
            throw new ProviderFailureException("La respuesta del proveedor no es un GLB");
        }
        return new GeneratedModel(glb.clone());
    }

    private static boolean hasGlbMagic(byte[] data) {
        if (data.length < GLB_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < GLB_MAGIC.length; i++) {
            if (data[i] != GLB_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public int sizeInBytes() {
        return glb.length;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(glb);
    }

    public byte[] toByteArray() {
        return glb.clone();
    }
}
