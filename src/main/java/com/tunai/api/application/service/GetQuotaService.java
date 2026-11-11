package com.tunai.api.application.service;

import com.tunai.api.application.port.in.GetQuotaUseCase;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.domain.model.AccountId;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.QuotaStatus;
import java.util.Objects;

public class GetQuotaService implements GetQuotaUseCase {

    private final QuotaStorePort quota;

    public GetQuotaService(QuotaStorePort quota) {
        this.quota = Objects.requireNonNull(quota, "quota");
    }

    @Override
    public QuotaStatus getQuota(AccountId accountId, ClientIp clientIp) {
        return quota.status(accountId, clientIp);
    }
}
