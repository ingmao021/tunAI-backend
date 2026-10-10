package com.tunai.api.adapter.in.web;

import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.in.GenerateModelUseCase.GenerateCommand;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.VehicleImage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** F-BE-01: {@code POST /api/v1/generations} (RF-BE-001). Solo traduce HTTP ↔ caso de uso. */
@RestController
@RequestMapping("/api/v1")
public class GenerationController {

    static final String GLB_MEDIA_TYPE = "model/gltf-binary";

    private final GenerateModelUseCase generateModel;
    private final ImageRequestMapper imageMapper;

    public GenerationController(GenerateModelUseCase generateModel, ImageRequestMapper imageMapper) {
        this.generateModel = generateModel;
        this.imageMapper = imageMapper;
    }

    /**
     * Escribe el GLB directamente en la respuesta para no copiar en memoria un modelo de varios MB (RNF-BE-003). Los
     * headers {@code X-Quota-*} se agregan en la Fase 3.
     */
    @PostMapping(path = "/generations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void generate(
            InstallationId installationId,
            @RequestPart(name = "image", required = false) MultipartFile image,
            HttpServletResponse response)
            throws IOException {
        VehicleImage vehicleImage = imageMapper.toVehicleImage(image);
        GeneratedModel model = generateModel.generate(new GenerateCommand(installationId, vehicleImage));

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(GLB_MEDIA_TYPE);
        response.setContentLengthLong(model.sizeInBytes());
        model.writeTo(response.getOutputStream());
    }
}
