package com.tunai.api.application.port.in;

import com.tunai.api.domain.model.AccountId;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.QuotaStatus;
import com.tunai.api.domain.model.VehicleImage;
import java.util.Objects;

/** F-BE-01: generar un modelo 3D a partir de una foto ya validada, dentro de la cuota. */
public interface GenerateModelUseCase {

    GenerationResult generate(GenerateCommand command);

    record GenerateCommand(AccountId accountId, ClientIp clientIp, VehicleImage image) {

        public GenerateCommand {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(clientIp, "clientIp");
            Objects.requireNonNull(image, "image");
        }
    }

    /** El modelo y el estado de cuota después de confirmar la generación (headers {@code X-Quota-*}). */
    record GenerationResult(GeneratedModel model, QuotaStatus quota) {

        public GenerationResult {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(quota, "quota");
        }
    }
}
