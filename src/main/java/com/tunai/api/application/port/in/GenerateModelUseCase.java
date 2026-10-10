package com.tunai.api.application.port.in;

import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.VehicleImage;
import java.util.Objects;

/** F-BE-01: generar un modelo 3D a partir de una foto ya validada. */
public interface GenerateModelUseCase {

    GeneratedModel generate(GenerateCommand command);

    record GenerateCommand(InstallationId installationId, VehicleImage image) {

        public GenerateCommand {
            Objects.requireNonNull(installationId, "installationId");
            Objects.requireNonNull(image, "image");
        }
    }
}
