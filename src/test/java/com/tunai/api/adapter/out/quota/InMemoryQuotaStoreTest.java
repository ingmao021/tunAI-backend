package com.tunai.api.adapter.out.quota;

import com.tunai.api.MutableClock;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.application.port.out.QuotaStorePortContract;
import com.tunai.api.domain.model.QuotaLimits;

class InMemoryQuotaStoreTest extends QuotaStorePortContract {

    @Override
    protected QuotaStorePort createStore(QuotaLimits limits, MutableClock clock) {
        return new InMemoryQuotaStore(limits, clock);
    }
}
