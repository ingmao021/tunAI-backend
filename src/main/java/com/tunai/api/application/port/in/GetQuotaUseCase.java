package com.tunai.api.application.port.in;

import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaStatus;

/** F-BE-02: consultar la cuota sin consumirla. */
public interface GetQuotaUseCase {

    QuotaStatus getQuota(InstallationId installationId, ClientIp clientIp);
}
