package com.tunai.api.adapter.in.web;

import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.in.GenerateModelUseCase.GenerateCommand;
import com.tunai.api.application.port.in.GenerateModelUseCase.GenerationResult;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.VehicleImage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GenerationController.class);

    private final GenerateModelUseCase generateModel;
    private final ImageRequestMapper imageMapper;

    public GenerationController(GenerateModelUseCase generateModel, ImageRequestMapper imageMapper) {
        this.generateModel = generateModel;
        this.imageMapper = imageMapper;
    }

    /** Escribe el GLB directamente en la respuesta para no copiar en memoria un modelo de varios MB (RNF-BE-003). */
    @PostMapping(path = "/generations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void generate(
            InstallationId installationId,
            ClientIp clientIp,
            @RequestPart(name = "image", required = false) MultipartFile image,
            HttpServletResponse response)
            throws IOException {
        VehicleImage vehicleImage = imageMapper.toVehicleImage(image);
        GenerationResult result = generateModel.generate(new GenerateCommand(installationId, clientIp, vehicleImage));

        // RNF-BE-009: sin imagen ni modelo; IP enmascarada e instalación como hash
        log.info(
                "Generación exitosa: instalación {}, IP {}, {} bytes, cuota restante {}/{}",
                installationId.hashForLogs(),
                clientIp.masked(),
                result.model().sizeInBytes(),
                result.quota().remaining(),
                result.quota().limit());

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(GLB_MEDIA_TYPE);
        response.setContentLengthLong(result.model().sizeInBytes());
        QuotaHeaders.write(response, result.quota());
        result.model().writeTo(response.getOutputStream());
    }
}
